package com.loanops.source.service;

import com.loanops.source.client.RawSourceResponse;
import com.loanops.source.client.SeoulLoanRateAdapter;
import com.loanops.source.client.SourceAdapter;
import com.loanops.source.client.SourceResponseValidation;
import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.SourceRunErrorType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class SourceIngestionService {

    private final Map<DataSourceCode, SourceAdapter> adapters;
    private final SourceRunLifecycleService lifecycleService;
    private final SourceErrorClassifier errorClassifier;

    public SourceIngestionService(
            List<SourceAdapter> adapters,
            SourceRunLifecycleService lifecycleService,
            SourceErrorClassifier errorClassifier
    ) {
        this.adapters = indexAdapters(adapters);
        this.lifecycleService = lifecycleService;
        this.errorClassifier = errorClassifier;
    }

    public IngestionResult ingest(DataSourceCode sourceCode) {
        SourceAdapter adapter = findAdapter(sourceCode);
        long sourceRunId = lifecycleService.start(sourceCode);

        if (adapter instanceof SeoulLoanRateAdapter seoulAdapter) {
            return ingestSeoul(sourceRunId, sourceCode, seoulAdapter);
        }

        RawSourceResponse response = fetch(sourceRunId, sourceCode, adapter::fetch);
        String payloadHash = preserveAndValidate(sourceRunId, sourceCode, adapter, response);
        completeSuccess(sourceRunId, sourceCode, response.httpStatus());
        return new IngestionResult(sourceRunId, sourceCode, response.httpStatus(), payloadHash, 1);
    }

    private IngestionResult ingestSeoul(
            long sourceRunId,
            DataSourceCode sourceCode,
            SeoulLoanRateAdapter adapter
    ) {
        int pageSize = adapter.pageSize();
        int startIndex = 1;
        int endIndex = pageSize;
        int snapshotCount = 0;
        int firstStart = startIndex;
        int firstEnd = endIndex;

        RawSourceResponse response = fetch(
                sourceRunId,
                sourceCode,
                () -> adapter.fetchPage(firstStart, firstEnd)
        );
        String payloadHash = preserveAndValidate(sourceRunId, sourceCode, adapter, response);
        snapshotCount++;

        int totalCount;
        try {
            totalCount = adapter.totalCount(response);
            requireExpectedRows(adapter, response, Math.min(pageSize, totalCount));
        } catch (RuntimeException exception) {
            throw persistFailure(
                    sourceRunId,
                    sourceCode,
                    new SourceFailure(
                            SourceRunErrorType.UPSTREAM_ERROR,
                            "Seoul response contains invalid pagination metadata",
                            response.httpStatus()
                    )
            );
        }

        for (startIndex = pageSize + 1; startIndex <= totalCount; startIndex += pageSize) {
            endIndex = Math.min(startIndex + pageSize - 1, totalCount);
            int pageStart = startIndex;
            int pageEnd = endIndex;
            response = fetch(
                    sourceRunId,
                    sourceCode,
                    () -> adapter.fetchPage(pageStart, pageEnd)
            );
            payloadHash = preserveAndValidate(sourceRunId, sourceCode, adapter, response);
            snapshotCount++;
            try {
                requireExpectedRows(adapter, response, pageEnd - pageStart + 1);
            } catch (RuntimeException exception) {
                throw persistFailure(
                        sourceRunId,
                        sourceCode,
                        new SourceFailure(
                                SourceRunErrorType.UPSTREAM_ERROR,
                                "Seoul response contains an unexpected row count",
                                response.httpStatus()
                        )
                );
            }
        }

        completeSuccess(sourceRunId, sourceCode, response.httpStatus());
        return new IngestionResult(
                sourceRunId,
                sourceCode,
                response.httpStatus(),
                payloadHash,
                snapshotCount
        );
    }

    private RawSourceResponse fetch(
            long sourceRunId,
            DataSourceCode sourceCode,
            ResponseSupplier responseSupplier
    ) {

        try {
            return responseSupplier.get();
        } catch (RuntimeException exception) {
            throw persistFailure(sourceRunId, sourceCode, errorClassifier.classify(exception));
        }
    }

    private String preserveAndValidate(
            long sourceRunId,
            DataSourceCode sourceCode,
            SourceAdapter adapter,
            RawSourceResponse response
    ) {
        String payloadHash;
        try {
            payloadHash = lifecycleService.preserveRawSnapshot(sourceRunId, response);
        } catch (RuntimeException exception) {
            SourceFailure failure = new SourceFailure(
                    SourceRunErrorType.UNKNOWN_ERROR,
                    "HTTP response body could not be preserved",
                    response.httpStatus()
            );
            throw persistFailure(sourceRunId, sourceCode, failure);
        }

        if (response.httpStatus() < 200 || response.httpStatus() > 299) {
            throw persistFailure(
                    sourceRunId,
                    sourceCode,
                    new SourceFailure(
                            SourceRunErrorType.HTTP_ERROR,
                            "External API returned HTTP status " + response.httpStatus(),
                            response.httpStatus()
                    )
            );
        }

        SourceResponseValidation validation;
        try {
            validation = adapter.validate(response);
        } catch (RuntimeException exception) {
            validation = SourceResponseValidation.failure(null);
        }
        if (!validation.successful()) {
            throw persistFailure(
                    sourceRunId,
                    sourceCode,
                    new SourceFailure(
                            SourceRunErrorType.UPSTREAM_ERROR,
                            upstreamErrorMessage(validation.resultCode()),
                            response.httpStatus()
                    )
            );
        }

        return payloadHash;
    }

    private void completeSuccess(long sourceRunId, DataSourceCode sourceCode, int httpStatus) {
        try {
            lifecycleService.completeSuccess(sourceRunId, httpStatus);
        } catch (RuntimeException exception) {
            SourceFailure failure = new SourceFailure(
                    SourceRunErrorType.UNKNOWN_ERROR,
                    "Successful source response could not be completed",
                    httpStatus
            );
            throw persistFailure(sourceRunId, sourceCode, failure);
        }
    }

    private void requireExpectedRows(SeoulLoanRateAdapter adapter, RawSourceResponse response, int expectedRows) {
        if (adapter.rowCount(response) != expectedRows) {
            throw new IllegalArgumentException("Unexpected Seoul row count");
        }
    }

    private SourceIngestionException persistFailure(
            long sourceRunId,
            DataSourceCode sourceCode,
            SourceFailure failure
    ) {
        lifecycleService.completeFailure(sourceRunId, failure);
        return new SourceIngestionException(
                sourceRunId,
                sourceCode,
                failure.errorType(),
                failure.errorMessage()
        );
    }

    private SourceAdapter findAdapter(DataSourceCode sourceCode) {
        SourceAdapter adapter = adapters.get(sourceCode);
        if (adapter == null) {
            throw new IllegalArgumentException("No source adapter configured for: " + sourceCode);
        }
        return adapter;
    }

    private String upstreamErrorMessage(String resultCode) {
        if (resultCode == null || resultCode.isBlank()) {
            return "Upstream source response is missing a valid result code";
        }
        String sanitizedCode = resultCode.replaceAll("[^A-Za-z0-9_-]", "?");
        if (sanitizedCode.length() > 64) {
            sanitizedCode = sanitizedCode.substring(0, 64);
        }
        return "Upstream source returned result code " + sanitizedCode;
    }

    private Map<DataSourceCode, SourceAdapter> indexAdapters(List<SourceAdapter> sourceAdapters) {
        Map<DataSourceCode, SourceAdapter> indexed = new EnumMap<>(DataSourceCode.class);
        for (SourceAdapter adapter : sourceAdapters) {
            if (indexed.put(adapter.sourceCode(), adapter) != null) {
                throw new IllegalStateException("Duplicate source adapter: " + adapter.sourceCode());
            }
        }
        return Map.copyOf(indexed);
    }

    @FunctionalInterface
    private interface ResponseSupplier {

        RawSourceResponse get();
    }
}
