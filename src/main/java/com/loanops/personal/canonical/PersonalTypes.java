package com.loanops.personal.canonical;

public final class PersonalTypes {
    private PersonalTypes() { }
    public enum SourceType { REAL_PRODUCT_DATA, OPEN_BANKING_TEST, PUBLIC_MYDATA_MOCK, USER_INPUT, DERIVED, AIHUB_OFFLINE }
    public enum SourceMode { LIVE_TEST, DEMO_REPLAY, OFFLINE, MANUAL }
    public enum ProviderCode { OPEN_BANKING, PUBLIC_MYDATA, AIHUB_OFFLINE }
    public enum Capability { BALANCE, TRANSACTIONS, CARDS, LOANS, LEASES, INVESTMENTS, INCOME }
    public enum Direction { CREDIT, DEBIT }
    public enum ConsentKind { DEMO, DEVELOPER_TEST }
    public enum ConsentStatus { ACTIVE, EXPIRED, REVOKED }
    public enum ValidationState { VALIDATED_COMPLETE, PARTIAL, STALE, INVALID }
    public enum Freshness { FRESH, STALE, NOT_EVALUATED }
    public enum RuleCode { DUPLICATE_TRANSACTION, PAGINATION_INCOMPLETE, STALE_BALANCE,
        MISSING_INCOME_EVIDENCE, LOAN_PAYMENT_MISMATCH, SOURCE_CAPABILITY_MISSING,
        SOURCE_SYNC_FAILED, INVALID_SOURCE_DATA, DISJOINT_TRANSACTION_WINDOW, OLDER_OBSERVATION }
    public enum Severity { INFO, WARNING, ERROR }
    public enum Resolution { OPEN, RESOLVED }
}
