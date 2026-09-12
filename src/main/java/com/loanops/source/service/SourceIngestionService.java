package com.loanops.source.service;

import com.loanops.source.client.RawSourceResponse;
import com.loanops.source.client.SourceAdapter;
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

        try {
            String payloadHash = lifecycleService.completeSuccess(sourceRunId, response);
            return new IngestionResult(sourceRunId, sourceCode, response.httpStatus(), payloadHash);
        } catch (RuntimeException exception) {
            SourceFailure failure = new SourceFailure(
                    SourceRunErrorType.UNKNOWN_ERROR,
                    "Successful response could not be persisted",
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
