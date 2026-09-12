package com.loanops.source.service;

import com.loanops.source.domain.DataSourceCode;
import com.loanops.source.domain.SourceRunErrorType;

public class SourceIngestionException extends RuntimeException {

    private final long sourceRunId;
    private final DataSourceCode sourceCode;
    private final SourceRunErrorType errorType;

    public SourceIngestionException(
            long sourceRunId,
            DataSourceCode sourceCode,
            SourceRunErrorType errorType,
            String sanitizedMessage
    ) {
        super(sanitizedMessage);
        this.sourceRunId = sourceRunId;
        this.sourceCode = sourceCode;
        this.errorType = errorType;
    }

    public long getSourceRunId() {
        return sourceRunId;
    }

    public DataSourceCode getSourceCode() {
        return sourceCode;
    }

    public SourceRunErrorType getErrorType() {
        return errorType;
    }
}
