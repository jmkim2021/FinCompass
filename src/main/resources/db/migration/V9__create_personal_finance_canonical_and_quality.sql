-- Personal canonical records and validation are separate from product publication/serving.
-- Financial payloads are encrypted; relational metadata supports ownership, lineage and bounded reads.
CREATE TABLE loanops.financial_users (
    id UUID PRIMARY KEY,
    owner_ref VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE loanops.consent_grants (
    id UUID PRIMARY KEY,
    financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
    source_provider VARCHAR(32) NOT NULL CHECK(source_provider IN ('OPEN_BANKING','PUBLIC_MYDATA','AIHUB_OFFLINE')),
    source_connection_id UUID NOT NULL REFERENCES loanops.open_banking_connections(id),
    source_mode VARCHAR(20) NOT NULL CHECK(source_mode IN ('LIVE_TEST','DEMO_REPLAY','OFFLINE','MANUAL')),
    consent_kind VARCHAR(24) NOT NULL CHECK(consent_kind IN ('DEMO','DEVELOPER_TEST')),
    scopes TEXT[] NOT NULL CHECK(cardinality(scopes)>0 AND scopes <@ ARRAY['BALANCE','TRANSACTIONS','CARDS','LOANS','LEASES','INVESTMENTS','INCOME']::text[]),
    granted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK(expires_at>granted_at),
    CHECK(revoked_at IS NULL OR revoked_at>=granted_at)
);
CREATE INDEX personal_consent_connection ON loanops.consent_grants(source_connection_id,granted_at DESC,id);
CREATE TABLE loanops.financial_accounts (
    id UUID PRIMARY KEY,
    financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
    source_provider VARCHAR(32) NOT NULL,
    source_connection_id UUID NOT NULL REFERENCES loanops.open_banking_connections(id),
    source_account_key VARCHAR(100) NOT NULL,
    provenance_id UUID NOT NULL,
    payload_ciphertext TEXT NOT NULL,
    UNIQUE(source_provider,source_account_key),
    UNIQUE(source_connection_id)
);
CREATE TABLE loanops.personal_validation_runs (
    id UUID PRIMARY KEY,
    validation_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    consent_grant_id UUID NOT NULL REFERENCES loanops.consent_grants(id),
    source_sync_run_id UUID NOT NULL REFERENCES loanops.source_sync_runs(id),
    financial_account_id UUID REFERENCES loanops.financial_accounts(id),
    capability VARCHAR(20) NOT NULL CHECK(capability IN ('BALANCE','TRANSACTIONS','CARDS','LOANS','LEASES','INVESTMENTS','INCOME')),
    state VARCHAR(24) NOT NULL CHECK(state IN ('VALIDATED_COMPLETE','PARTIAL','STALE','INVALID')),
    promoted BOOLEAN NOT NULL,
    requested_from DATE, requested_through DATE,
    covered_from DATE, covered_through DATE,
    source_started_at TIMESTAMPTZ NOT NULL,
    as_of TIMESTAMPTZ,
    evaluated_at TIMESTAMPTZ NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    balance_stale_after_seconds BIGINT NOT NULL CHECK(balance_stale_after_seconds>0),
    content_hash VARCHAR(64),
    CHECK(NOT promoted OR (state='VALIDATED_COMPLETE' AND financial_account_id IS NOT NULL)),
    CHECK((requested_from IS NULL)=(requested_through IS NULL)),
    CHECK(requested_from IS NULL OR requested_through>=requested_from),
    CHECK((covered_from IS NULL)=(covered_through IS NULL)),
    CHECK(covered_from IS NULL OR covered_through>=covered_from),
    CHECK(content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE(source_sync_run_id,consent_grant_id,policy_version,balance_stale_after_seconds),
    UNIQUE(id,financial_account_id,capability)
);
CREATE INDEX personal_validation_history ON loanops.personal_validation_runs(consent_grant_id,capability,validation_sequence DESC);
CREATE TABLE loanops.personal_source_evidence (
    id UUID PRIMARY KEY,
    validation_run_id UUID NOT NULL REFERENCES loanops.personal_validation_runs(id),
    source_sync_run_id UUID NOT NULL REFERENCES loanops.source_sync_runs(id),
    raw_snapshot_id UUID NOT NULL REFERENCES loanops.raw_openbanking_snapshots(id),
    source_type VARCHAR(32) NOT NULL CHECK(source_type IN ('REAL_PRODUCT_DATA','OPEN_BANKING_TEST','PUBLIC_MYDATA_MOCK','USER_INPUT','DERIVED','AIHUB_OFFLINE')),
    source_mode VARCHAR(20) NOT NULL CHECK(source_mode IN ('LIVE_TEST','DEMO_REPLAY','OFFLINE','MANUAL')),
    entity_kind VARCHAR(24) NOT NULL CHECK(entity_kind IN ('ACCOUNT','BALANCE','TRANSACTION','LOAN','LOAN_PAYMENT','CARD','CARD_BILL','INVESTMENT','INCOME')),
    as_of TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    normalized_at TIMESTAMPTZ NOT NULL,
    raw_record_index INTEGER CHECK(raw_record_index>=0),
    UNIQUE NULLS NOT DISTINCT(validation_run_id,raw_snapshot_id,entity_kind,raw_record_index)
);
ALTER TABLE loanops.financial_accounts ADD CONSTRAINT personal_account_provenance
    FOREIGN KEY(provenance_id) REFERENCES loanops.personal_source_evidence(id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE loanops.balance_snapshots (
    id UUID PRIMARY KEY,
    financial_account_id UUID NOT NULL REFERENCES loanops.financial_accounts(id),
    provenance_id UUID NOT NULL UNIQUE REFERENCES loanops.personal_source_evidence(id),
    as_of TIMESTAMPTZ NOT NULL,
    payload_ciphertext TEXT NOT NULL
);
CREATE INDEX personal_balance_history ON loanops.balance_snapshots(financial_account_id,as_of DESC,id);
CREATE TABLE loanops.financial_transactions (
    id UUID PRIMARY KEY,
    financial_account_id UUID NOT NULL REFERENCES loanops.financial_accounts(id),
    source_transaction_key VARCHAR(64) NOT NULL CHECK(source_transaction_key ~ '^[0-9a-f]{64}$'),
    provenance_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    payload_ciphertext TEXT NOT NULL,
    UNIQUE(financial_account_id,source_transaction_key)
);
CREATE INDEX personal_transaction_account_time ON loanops.financial_transactions(financial_account_id,occurred_at,id);
-- Each promoted validation has an immutable membership manifest, including inherited validated records.
CREATE TABLE loanops.personal_validation_transactions (
    validation_run_id UUID NOT NULL REFERENCES loanops.personal_validation_runs(id),
    transaction_id UUID NOT NULL REFERENCES loanops.financial_transactions(id),
    observation_evidence_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    PRIMARY KEY(validation_run_id,transaction_id)
);
CREATE TABLE loanops.personal_current_states (
    financial_account_id UUID NOT NULL REFERENCES loanops.financial_accounts(id),
    capability VARCHAR(20) NOT NULL CHECK(capability IN ('BALANCE','TRANSACTIONS')),
    validation_run_id UUID NOT NULL UNIQUE,
    PRIMARY KEY(financial_account_id,capability),
    FOREIGN KEY(validation_run_id,financial_account_id,capability)
        REFERENCES loanops.personal_validation_runs(id,financial_account_id,capability)
);
CREATE TABLE loanops.personal_quality_issues (
    id UUID PRIMARY KEY,
    validation_run_id UUID NOT NULL REFERENCES loanops.personal_validation_runs(id),
    rule_code VARCHAR(40) NOT NULL CHECK(rule_code IN ('DUPLICATE_TRANSACTION','PAGINATION_INCOMPLETE','STALE_BALANCE','MISSING_INCOME_EVIDENCE','LOAN_PAYMENT_MISMATCH','SOURCE_CAPABILITY_MISSING','SOURCE_SYNC_FAILED','INVALID_SOURCE_DATA','DISJOINT_TRANSACTION_WINDOW','OLDER_OBSERVATION')),
    severity VARCHAR(10) NOT NULL CHECK(severity IN ('INFO','WARNING','ERROR')),
    detected_at TIMESTAMPTZ NOT NULL,
    affected_entity VARCHAR(40) NOT NULL,
    details VARCHAR(250) NOT NULL,
    resolution_state VARCHAR(12) NOT NULL CHECK(resolution_state IN ('OPEN','RESOLVED')),
    UNIQUE(validation_run_id,rule_code,affected_entity)
);
-- Required domain boundaries, intentionally empty without an authorized source provider.
CREATE TABLE loanops.loan_accounts (
    id UUID PRIMARY KEY,
    financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
    provenance_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    payload_ciphertext TEXT NOT NULL
);
CREATE TABLE loanops.loan_payments (
    id UUID PRIMARY KEY,
    loan_account_id UUID NOT NULL REFERENCES loanops.loan_accounts(id),
    provenance_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    payload_ciphertext TEXT NOT NULL
);
CREATE TABLE loanops.card_accounts (
    id UUID PRIMARY KEY,
    financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
    payment_account_id UUID REFERENCES loanops.financial_accounts(id),
    provenance_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    payload_ciphertext TEXT NOT NULL
);
CREATE TABLE loanops.card_bills (
    id UUID PRIMARY KEY,
    card_account_id UUID NOT NULL REFERENCES loanops.card_accounts(id),
    provenance_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    payload_ciphertext TEXT NOT NULL
);
CREATE TABLE loanops.investment_positions (
    id UUID PRIMARY KEY,
    financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
    provenance_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    payload_ciphertext TEXT NOT NULL
);
CREATE TABLE loanops.income_evidence (
    id UUID PRIMARY KEY,
    financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
    provenance_id UUID NOT NULL REFERENCES loanops.personal_source_evidence(id),
    is_verified BOOLEAN NOT NULL,
    payload_ciphertext TEXT NOT NULL
);
