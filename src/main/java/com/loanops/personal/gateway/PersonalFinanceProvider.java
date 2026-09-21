package com.loanops.personal.gateway;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.loanops.personal.canonical.PersonalTypes.*;

/** Neutral source boundary. Implementations may read existing ingestion evidence; this API does not trigger HTTP sync. */
public interface PersonalFinanceProvider {
    ProviderCode code();
    SourceConnection connection(UUID connectionId, String owner);
    SourceBatch read(UUID runId, String owner);

    record SourceConnection(UUID id, SourceMode mode, Instant consentUntil) {
        @Override public String toString() { return "SourceConnection[REDACTED]"; }
    }
    record Evidence(UUID rawId, Instant asOf, Instant collectedAt, String endpoint, String hash, Integer rowIndex) {
        @Override public String toString() { return "Evidence[REDACTED]"; }
    }
    record AccountObservation(String sourceAccountKey, String institution, String accountType, String currency, Evidence evidence) {
        @Override public String toString() { return "AccountObservation[REDACTED]"; }
    }
    record BalanceObservation(BigDecimal balance, BigDecimal available, String currency, Evidence evidence) {
        @Override public String toString() { return "BalanceObservation[REDACTED]"; }
    }
    record TransactionObservation(String sourceKey, Instant occurredAt, Instant postedAt, BigDecimal amount,
            Direction direction, String description, String merchant, String category, BigDecimal balanceAfter,
            String currency, Evidence evidence) {
        @Override public String toString() { return "TransactionObservation[REDACTED]"; }
    }
    record SourceBatch(UUID runId, UUID connectionId, SourceType sourceType, SourceMode sourceMode, Capability capability,
            String sourceStatus, boolean complete, String sourceError, LocalDate from, LocalDate through,
            Instant startedAt, Instant completedAt, int duplicateCount, Set<Capability> supported,
            AccountObservation account, BalanceObservation balance, List<TransactionObservation> transactions,
            boolean mappingValid) {
        public SourceBatch { supported=Set.copyOf(supported); transactions=List.copyOf(transactions); }
        @Override public String toString() { return "SourceBatch[capability="+capability+", complete="+complete+"]"; }
    }
}
