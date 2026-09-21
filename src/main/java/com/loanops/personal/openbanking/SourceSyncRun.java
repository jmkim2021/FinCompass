package com.loanops.personal.openbanking;

import java.time.*;
import java.util.UUID;

/** complete means the requested endpoint/range completed, never a complete Financial Twin. */
public record SourceSyncRun(UUID id, UUID connectionId, String source, OpenBankingMode mode,
        OpenBankingCapability capability, String status, LocalDate requestedFrom, LocalDate requestedTo,
        Instant startedAt, Instant completedAt, int pageCount, int recordCount, int insertedCount,
        int duplicateCount, boolean complete, String errorCategory, String failureCause) { }
