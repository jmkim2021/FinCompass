CREATE TABLE loanops.financial_snapshot (
 id UUID PRIMARY KEY, financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
 as_of TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL, status VARCHAR(24) NOT NULL,
 calculation_rule_version VARCHAR(80) NOT NULL, input_fingerprint VARCHAR(64) NOT NULL,
 payload_ciphertext TEXT NOT NULL, UNIQUE(financial_user_id,input_fingerprint)
);
CREATE INDEX financial_snapshot_current ON loanops.financial_snapshot(financial_user_id,created_at DESC);
CREATE TABLE loanops.financial_snapshot_source_ref (
 snapshot_id UUID NOT NULL REFERENCES loanops.financial_snapshot(id), ordinal INTEGER NOT NULL,
 record_id UUID NOT NULL, provenance_id UUID, validation_run_id UUID,
 source_type VARCHAR(40) NOT NULL, source_mode VARCHAR(32) NOT NULL,
 as_of TIMESTAMPTZ NOT NULL, content_hash VARCHAR(64) NOT NULL, purpose VARCHAR(100) NOT NULL,
 PRIMARY KEY(snapshot_id,ordinal)
);
CREATE TABLE loanops.financial_metric (
 snapshot_id UUID NOT NULL REFERENCES loanops.financial_snapshot(id), name VARCHAR(60) NOT NULL,
 availability VARCHAR(30) NOT NULL, payload_ciphertext TEXT NOT NULL, PRIMARY KEY(snapshot_id,name)
);
CREATE TABLE loanops.user_entered_financial_fact (
 id UUID PRIMARY KEY, financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
 kind VARCHAR(60) NOT NULL, as_of TIMESTAMPTZ NOT NULL, payload_ciphertext TEXT NOT NULL
);
CREATE FUNCTION loanops.reject_financial_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Immutable financial history'; END; $$;
CREATE TRIGGER immutable_financial_snapshot BEFORE UPDATE OR DELETE ON loanops.financial_snapshot FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_financial_metric BEFORE UPDATE OR DELETE ON loanops.financial_metric FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_financial_source_ref BEFORE UPDATE OR DELETE ON loanops.financial_snapshot_source_ref FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_financial_fact BEFORE UPDATE OR DELETE ON loanops.user_entered_financial_fact FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
