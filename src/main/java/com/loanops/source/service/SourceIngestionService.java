package com.loanops.source.service;

import com.loanops.source.client.RawSourceResponse;
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

        RawSourceResponse response;
        try {
            response = adapter.fetch();
        } catch (RuntimeException exception) {
            throw persistFailure(sourceRunId, sourceCode, errorClassifier.classify(exception));
        }

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

        try {
            lifecycleService.completeSuccess(sourceRunId, response.httpStatus());
            return new IngestionResult(sourceRunId, sourceCode, response.httpStatus(), payloadHash);
        } catch (RuntimeException exception) {
            SourceFailure failure = new SourceFailure(
                    SourceRunErrorType.UNKNOWN_ERROR,
                    "Successful source response could not be completed",
                    response.httpStatus()
            );
            throw persistFailure(sourceRunId, sourceCode, failure);
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
}
