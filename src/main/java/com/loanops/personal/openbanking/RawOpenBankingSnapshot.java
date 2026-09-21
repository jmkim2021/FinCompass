package com.loanops.personal.openbanking;

import java.time.Instant;
import java.util.UUID;

/** Metadata only. Encrypted response bodies are deliberately absent from this DTO. */
public record RawOpenBankingSnapshot(UUID id, UUID syncRunId, String source, String endpoint,
        Instant requestedAt, Instant receivedAt, int httpStatus, String resultStatus,
        String responseHash, String apiVersion, boolean redacted, int pageNumber) { }
