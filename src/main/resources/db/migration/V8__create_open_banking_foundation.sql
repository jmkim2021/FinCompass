-- Personal integration evidence is separate from the existing trusted product pipeline.
-- No canonical Financial Twin/account/loan tables are introduced in Phase 1.
CREATE TABLE loanops.open_banking_connections (
    id UUID PRIMARY KEY,
    owner_ref VARCHAR(100) NOT NULL,
    mode VARCHAR(20) NOT NULL CHECK (mode IN ('LIVE_TEST','DEMO_REPLAY')),
    account_ciphertext TEXT NOT NULL,
    consent_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    token_ciphertext TEXT,
    token_expires_at TIMESTAMPTZ,
    CHECK ((token_ciphertext IS NULL) = (token_expires_at IS NULL))
);
CREATE TABLE loanops.open_banking_oauth_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES loanops.open_banking_connections(id),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE loanops.source_sync_runs (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES loanops.open_banking_connections(id),
    source VARCHAR(32) NOT NULL CHECK (source = 'OPEN_BANKING'),
    mode VARCHAR(20) NOT NULL CHECK (mode IN ('LIVE_TEST','DEMO_REPLAY')),
    capability VARCHAR(20) NOT NULL CHECK (capability IN ('BALANCE','TRANSACTIONS','CARDS','LOANS','LEASES')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','SUCCESS','FAILED','UNSUPPORTED')),
    requested_from DATE, requested_to DATE,
    started_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
    page_count INTEGER NOT NULL DEFAULT 0 CHECK (page_count >= 0),
    record_count INTEGER NOT NULL DEFAULT 0 CHECK (record_count >= 0),
    inserted_count INTEGER NOT NULL DEFAULT 0 CHECK (inserted_count >= 0),
    duplicate_count INTEGER NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
    complete BOOLEAN NOT NULL DEFAULT FALSE,
    error_category VARCHAR(64), failure_cause VARCHAR(64),
    CHECK (requested_from IS NULL OR requested_to >= requested_from),
    CHECK ((status = 'RUNNING' AND completed_at IS NULL AND NOT complete)
        OR (status = 'SUCCESS' AND completed_at IS NOT NULL AND complete AND error_category IS NULL)
        OR (status IN ('FAILED','UNSUPPORTED') AND completed_at IS NOT NULL AND NOT complete AND error_category IS NOT NULL))
);
CREATE UNIQUE INDEX source_sync_one_active_capability ON loanops.source_sync_runs(connection_id,capability) WHERE status='RUNNING';
CREATE INDEX source_sync_history ON loanops.source_sync_runs(connection_id,started_at DESC,id);
CREATE TABLE loanops.raw_openbanking_snapshots (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL REFERENCES loanops.source_sync_runs(id),
    source VARCHAR(32) NOT NULL CHECK (source='OPEN_BANKING'),
    endpoint VARCHAR(100) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL,
    http_status INTEGER NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    result_status VARCHAR(32),
    response_hash VARCHAR(64) NOT NULL CHECK (response_hash ~ '^[0-9a-f]{64}$'),
    payload_ciphertext TEXT NOT NULL,
    api_version VARCHAR(40) NOT NULL,
    redacted BOOLEAN NOT NULL,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    UNIQUE(sync_run_id,page_number)
);
-- Ingestion evidence/deduplication index, NOT the Phase 2 canonical transaction ledger.
CREATE TABLE loanops.open_banking_transaction_evidence (
    connection_id UUID NOT NULL REFERENCES loanops.open_banking_connections(id),
    identity_hash VARCHAR(64) NOT NULL,
    identity_rule_version VARCHAR(32) NOT NULL,
    first_raw_snapshot_id UUID NOT NULL REFERENCES loanops.raw_openbanking_snapshots(id),
    transaction_date DATE NOT NULL,
    mapped_ciphertext TEXT NOT NULL,
    PRIMARY KEY(connection_id,identity_hash)
);
CREATE TABLE loanops.open_banking_sync_checkpoints (
    connection_id UUID NOT NULL REFERENCES loanops.open_banking_connections(id),
    capability VARCHAR(20) NOT NULL CHECK (capability IN ('BALANCE','TRANSACTIONS')),
    successful_run_id UUID NOT NULL REFERENCES loanops.source_sync_runs(id),
    covered_from DATE, covered_through DATE,
    synced_at TIMESTAMPTZ NOT NULL,
    mapped_ciphertext TEXT,
    PRIMARY KEY(connection_id,capability),
    CHECK (covered_from IS NULL OR covered_through >= covered_from)
);
