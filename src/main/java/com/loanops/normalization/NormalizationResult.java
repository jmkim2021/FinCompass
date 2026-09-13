package com.loanops.normalization;

import com.loanops.source.domain.DataSourceCode;

public record NormalizationResult(
        long sourceRunId,
        DataSourceCode sourceCode,
        int snapshotCount,
        int productCount,
        int termCount,
        int observationCount
) {
}
