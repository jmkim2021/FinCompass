CREATE TABLE loanops.opportunity_event (
 id UUID PRIMARY KEY, financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
 financial_snapshot_id UUID NOT NULL REFERENCES loanops.financial_snapshot(id), scenario_id UUID REFERENCES loanops.scenario(id),
 product_match_run_id UUID REFERENCES loanops.product_match_run(id), type VARCHAR(40) NOT NULL,
 detected_at TIMESTAMPTZ NOT NULL, rule_version VARCHAR(80) NOT NULL, status VARCHAR(20) NOT NULL CHECK(status IN ('ACTIVE','RESOLVED')),
 event_key VARCHAR(64) NOT NULL, payload_ciphertext TEXT NOT NULL, UNIQUE(financial_user_id,event_key)
);
CREATE INDEX opportunity_owner_active ON loanops.opportunity_event(financial_user_id,status,detected_at DESC);
CREATE TRIGGER immutable_opportunity_event BEFORE UPDATE OR DELETE ON loanops.opportunity_event FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
