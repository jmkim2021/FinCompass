-- Preserve V8/V9 relations while adding provider-neutral linkage. No product tables change.
CREATE TABLE loanops.provider_connections (
 id UUID PRIMARY KEY, financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
 provider VARCHAR(32) NOT NULL CHECK(provider IN ('OPEN_BANKING','PUBLIC_MYDATA')),
 mode VARCHAR(20) NOT NULL CHECK(mode IN ('DEMO_REPLAY','LIVE_TEST')),
 consent_until TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 legacy_connection_id UUID UNIQUE REFERENCES loanops.open_banking_connections(id),
 CHECK((provider='OPEN_BANKING')=(legacy_connection_id IS NOT NULL)), UNIQUE(id,provider)
);
CREATE TABLE loanops.provider_sync_runs (
 id UUID PRIMARY KEY, connection_id UUID NOT NULL REFERENCES loanops.provider_connections(id),
 capability VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL CHECK(status IN ('SUCCESS','FAILED','UNSUPPORTED')),
 complete BOOLEAN NOT NULL, started_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ NOT NULL,
 legacy_run_id UUID UNIQUE REFERENCES loanops.source_sync_runs(id),
 CHECK(complete=(status='SUCCESS')), UNIQUE(id,connection_id)
);
CREATE TABLE loanops.provider_evidence (
 id UUID PRIMARY KEY, provider_sync_run_id UUID NOT NULL REFERENCES loanops.provider_sync_runs(id),
 fixture_evidence_id VARCHAR(80), contract_version VARCHAR(50), as_of TIMESTAMPTZ NOT NULL,
 collected_at TIMESTAMPTZ NOT NULL, response_hash VARCHAR(64) NOT NULL CHECK(response_hash ~ '^[0-9a-f]{64}$'),
 source_type VARCHAR(32) NOT NULL CHECK(source_type IN ('OPEN_BANKING_TEST','PUBLIC_MYDATA_MOCK')),
 source_mode VARCHAR(20) NOT NULL CHECK(source_mode IN ('DEMO_REPLAY','LIVE_TEST')),
 endpoint VARCHAR(100) NOT NULL, payload_ciphertext TEXT,
 legacy_raw_id UUID UNIQUE REFERENCES loanops.raw_openbanking_snapshots(id),
 consent_grant_id UUID REFERENCES loanops.consent_grants(id),
 CHECK((legacy_raw_id IS NOT NULL AND payload_ciphertext IS NULL AND source_type='OPEN_BANKING_TEST') OR
       (legacy_raw_id IS NULL AND payload_ciphertext IS NOT NULL AND source_type='PUBLIC_MYDATA_MOCK' AND consent_grant_id IS NOT NULL)),
 UNIQUE(id,provider_sync_run_id)
);
-- Import only connections which already have canonical owners; untouched V8-only connections are bridged on first grant.
INSERT INTO loanops.provider_connections
 SELECT DISTINCT c.id,u.id,'OPEN_BANKING',c.mode,c.consent_until,c.created_at,c.id
 FROM loanops.open_banking_connections c JOIN loanops.financial_users u ON u.owner_ref=c.owner_ref;
INSERT INTO loanops.provider_sync_runs
 SELECT s.id,s.connection_id,s.capability,s.status,s.complete,s.started_at,s.completed_at,s.id
 FROM loanops.source_sync_runs s JOIN loanops.provider_connections c ON c.id=s.connection_id WHERE s.status<>'RUNNING';
INSERT INTO loanops.provider_evidence
 SELECT r.id,r.sync_run_id,NULL,NULL,r.received_at,r.received_at,r.response_hash,'OPEN_BANKING_TEST',c.mode,r.endpoint,NULL,r.id,NULL
 FROM loanops.raw_openbanking_snapshots r JOIN loanops.provider_sync_runs s ON s.id=r.sync_run_id
 JOIN loanops.provider_connections c ON c.id=s.connection_id;
ALTER TABLE loanops.consent_grants ADD COLUMN provider_connection_id UUID REFERENCES loanops.provider_connections(id);
UPDATE loanops.consent_grants SET provider_connection_id=source_connection_id;
ALTER TABLE loanops.consent_grants ALTER COLUMN provider_connection_id SET NOT NULL;
ALTER TABLE loanops.consent_grants ALTER COLUMN source_connection_id DROP NOT NULL;
ALTER TABLE loanops.consent_grants ADD CONSTRAINT consent_provider_match FOREIGN KEY(provider_connection_id,source_provider) REFERENCES loanops.provider_connections(id,provider);
ALTER TABLE loanops.consent_grants ADD CONSTRAINT consent_legacy_link CHECK((source_provider='OPEN_BANKING' AND source_connection_id IS NOT NULL AND source_connection_id=provider_connection_id) OR (source_provider='PUBLIC_MYDATA' AND source_connection_id IS NULL));
ALTER TABLE loanops.consent_grants DROP CONSTRAINT consent_grants_scopes_check;
ALTER TABLE loanops.consent_grants ADD CONSTRAINT consent_provider_scopes CHECK(cardinality(scopes)>0 AND
 ((source_provider='OPEN_BANKING' AND scopes <@ ARRAY['BALANCE','TRANSACTIONS']::text[]) OR
 (source_provider='PUBLIC_MYDATA' AND scopes <@ ARRAY['INCOME','EMPLOYMENT']::text[])));
CREATE INDEX provider_consent_history ON loanops.consent_grants(provider_connection_id,granted_at DESC);
ALTER TABLE loanops.personal_validation_runs ADD COLUMN provider_sync_run_id UUID REFERENCES loanops.provider_sync_runs(id);
UPDATE loanops.personal_validation_runs SET provider_sync_run_id=source_sync_run_id;
ALTER TABLE loanops.personal_validation_runs ALTER COLUMN provider_sync_run_id SET NOT NULL;
ALTER TABLE loanops.personal_validation_runs ALTER COLUMN source_sync_run_id DROP NOT NULL;
ALTER TABLE loanops.personal_validation_runs DROP CONSTRAINT personal_validation_runs_capability_check;
ALTER TABLE loanops.personal_validation_runs ADD CONSTRAINT validation_capability CHECK(capability IN ('BALANCE','TRANSACTIONS','CARDS','LOANS','LEASES','INVESTMENTS','INCOME','EMPLOYMENT'));
ALTER TABLE loanops.personal_validation_runs DROP CONSTRAINT personal_validation_runs_check;
ALTER TABLE loanops.personal_validation_runs ADD CONSTRAINT validation_promoted CHECK(NOT promoted OR (state='VALIDATED_COMPLETE' AND (financial_account_id IS NOT NULL OR capability IN ('INCOME','EMPLOYMENT'))));
ALTER TABLE loanops.personal_validation_runs ADD COLUMN income_stale_after_seconds BIGINT;
ALTER TABLE loanops.personal_validation_runs ADD COLUMN max_income_year_age INTEGER;
CREATE UNIQUE INDEX provider_validation_idempotency ON loanops.personal_validation_runs(provider_sync_run_id,consent_grant_id,policy_version,balance_stale_after_seconds);
ALTER TABLE loanops.personal_source_evidence ADD COLUMN provider_evidence_id UUID REFERENCES loanops.provider_evidence(id);
ALTER TABLE loanops.personal_source_evidence ADD COLUMN provider_sync_run_id UUID REFERENCES loanops.provider_sync_runs(id);
UPDATE loanops.personal_source_evidence SET provider_evidence_id=raw_snapshot_id,provider_sync_run_id=source_sync_run_id;
ALTER TABLE loanops.personal_source_evidence ALTER COLUMN provider_evidence_id SET NOT NULL;
ALTER TABLE loanops.personal_source_evidence ALTER COLUMN provider_sync_run_id SET NOT NULL;
ALTER TABLE loanops.personal_source_evidence ALTER COLUMN raw_snapshot_id DROP NOT NULL;
ALTER TABLE loanops.personal_source_evidence ALTER COLUMN source_sync_run_id DROP NOT NULL;
ALTER TABLE loanops.personal_source_evidence ADD CONSTRAINT provider_evidence_run_match FOREIGN KEY(provider_evidence_id,provider_sync_run_id) REFERENCES loanops.provider_evidence(id,provider_sync_run_id);
CREATE UNIQUE INDEX provider_canonical_evidence_unique ON loanops.personal_source_evidence(validation_run_id,provider_evidence_id,entity_kind,raw_record_index) NULLS NOT DISTINCT;
ALTER TABLE loanops.personal_quality_issues DROP CONSTRAINT personal_quality_issues_rule_code_check;
ALTER TABLE loanops.personal_quality_issues ADD CONSTRAINT personal_quality_rule CHECK(rule_code IN ('DUPLICATE_TRANSACTION','PAGINATION_INCOMPLETE','STALE_BALANCE','MISSING_INCOME_EVIDENCE','LOAN_PAYMENT_MISMATCH','SOURCE_CAPABILITY_MISSING','SOURCE_SYNC_FAILED','INVALID_SOURCE_DATA','DISJOINT_TRANSACTION_WINDOW','OLDER_OBSERVATION','STALE_INCOME_EVIDENCE','INCOME_YEAR_OUTDATED','CONFLICTING_INCOME_EVIDENCE','CONSENT_MISSING','CONSENT_EXPIRED','CONSENT_REVOKED','CONSENT_SCOPE_DENIED'));
CREATE TABLE loanops.provider_current_evidence (
 provider_connection_id UUID NOT NULL REFERENCES loanops.provider_connections(id),
 capability VARCHAR(20) NOT NULL CHECK(capability IN ('INCOME','EMPLOYMENT')),
 validation_run_id UUID NOT NULL REFERENCES loanops.personal_validation_runs(id),
 PRIMARY KEY(provider_connection_id,capability)
);
CREATE TABLE loanops.provider_access_issues (
 id UUID PRIMARY KEY, provider_connection_id UUID NOT NULL REFERENCES loanops.provider_connections(id),
 capability VARCHAR(20) NOT NULL, rule_code VARCHAR(40) NOT NULL CHECK(rule_code IN ('CONSENT_MISSING','CONSENT_EXPIRED','CONSENT_REVOKED','CONSENT_SCOPE_DENIED')),
 detected_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX provider_evidence_run_index ON loanops.provider_evidence(provider_sync_run_id);
CREATE INDEX income_provenance_index ON loanops.income_evidence(provenance_id);

-- Cross-user connection references and mismatched run/evidence cannot be inserted even via direct SQL.
ALTER TABLE loanops.provider_connections ADD CONSTRAINT provider_owner_unique UNIQUE(id,financial_user_id);
ALTER TABLE loanops.consent_grants ADD CONSTRAINT consent_connection_owner FOREIGN KEY(provider_connection_id,financial_user_id) REFERENCES loanops.provider_connections(id,financial_user_id);
ALTER TABLE loanops.personal_validation_runs ADD CONSTRAINT validation_provider_run_unique UNIQUE(id,provider_sync_run_id);
ALTER TABLE loanops.personal_source_evidence ADD CONSTRAINT provenance_validation_run FOREIGN KEY(validation_run_id,provider_sync_run_id) REFERENCES loanops.personal_validation_runs(id,provider_sync_run_id);
