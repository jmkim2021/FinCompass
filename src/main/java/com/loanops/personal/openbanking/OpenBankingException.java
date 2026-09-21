package com.loanops.personal.openbanking;

/** Only fixed, non-sensitive categories cross the provider boundary. Never retain upstream causes. */
public final class OpenBankingException extends RuntimeException {
    public enum Category {
        CONFIGURATION, CONSENT_EXPIRED, NOT_FOUND, INVALID_REQUEST, INVALID_STATE,
        TOKEN_REQUIRED, TOKEN_EXPIRED, TOKEN_REFRESH_FAILED, TOKEN_EXCHANGE_FAILED,
        HTTP_ERROR, UPSTREAM_ERROR, TIMEOUT, CONNECTION_ERROR, INVALID_RESPONSE,
        PAGINATION_INCOMPLETE, SOURCE_CAPABILITY_MISSING, SYNC_ALREADY_RUNNING, STORAGE_ERROR
    }
    private final Category category;
    public OpenBankingException(Category category) {
        super("Open Banking operation failed: " + category.name());
        this.category = category;
    }
    public Category category() { return category; }
}
