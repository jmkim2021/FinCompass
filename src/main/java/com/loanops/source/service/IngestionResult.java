package com.loanops.source.service;

import com.loanops.source.domain.DataSourceCode;

public record IngestionResult(
        long sourceRunId,
        DataSourceCode sourceCode,
        int httpStatus,
        String payloadHash
) {
}
