package com.loanops.personal.openbanking;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class OpenBankingModels {
    private OpenBankingModels() { }
    public record Connection(UUID id, String ownerRef, OpenBankingMode mode,
                             @JsonIgnore String accountCiphertext, Instant consentUntil) {
        @Override public String toString() { return "Connection[id=" + id + ", mode=" + mode + "]"; }
    }
    public record Token(@JsonIgnore String accessToken, @JsonIgnore String refreshToken,
                        Instant expiresAt, String scope) {
        @Override public String toString() { return "Token[REDACTED, expiresAt=" + expiresAt + "]"; }
    }
    public record RawResponse(String endpoint, Instant requestedAt, Instant receivedAt,
                              int httpStatus, @JsonIgnore String body) {
        @Override public String toString() { return "RawResponse[endpoint=" + endpoint + ", status=" + httpStatus + "]"; }
    }
    public record Balance(BigDecimal balance, BigDecimal available, Instant observedAt, String sourceType) { }
    public record ObservedTransaction(LocalDate date, LocalTime time, String direction, String type,
                                      BigDecimal amount, BigDecimal balanceAfter, String description, String branch) {
        @Override public String toString() { return "ObservedTransaction[REDACTED]"; }
    }
    public record TransactionPage(List<ObservedTransaction> transactions, boolean hasNext, String nextCursor) { }
    public record TransactionEvidence(String identity, UUID rawSnapshotId, ObservedTransaction transaction) { }
    public record Checkpoint(LocalDate from, LocalDate through, UUID runId, Instant syncedAt) { }
    public record CapabilityState(OpenBankingCapability capability, boolean supported, String reason) { }
}
