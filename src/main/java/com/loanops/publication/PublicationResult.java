package com.loanops.publication;

import com.loanops.publication.domain.PublicationStatus;

public record PublicationResult(
        long sourceRunId,
        long publicationRunId,
        PublicationStatus status,
        long issueCount,
        long quarantineCount,
        long changeCount,
        boolean alreadyFinalized
) {
}
