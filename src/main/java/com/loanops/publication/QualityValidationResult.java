package com.loanops.publication;

public record QualityValidationResult(
        long sourceRunId,
        boolean valid,
        long issueCount,
        long quarantineCount
) {
}
