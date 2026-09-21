-- Phase 5: reproducible simulations refer to immutable Phase 4 inputs; no product or agent schema.
CREATE TABLE loanops.scenario (
 id UUID PRIMARY KEY,
 financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
 financial_snapshot_id UUID NOT NULL REFERENCES loanops.financial_snapshot(id),
 kind VARCHAR(24) NOT NULL CHECK (kind IN ('LOAN','HOME','STRESS','COMPARISON')),
 parent_scenario_id UUID REFERENCES loanops.scenario(id),
 calculation_rule_version VARCHAR(80) NOT NULL,
 simulation_rule_version VARCHAR(80) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 CHECK ((kind = 'STRESS' AND parent_scenario_id IS NOT NULL) OR (kind <> 'STRESS' AND parent_scenario_id IS NULL))
);
CREATE INDEX scenario_owner_history ON loanops.scenario(financial_user_id,created_at DESC,id);
CREATE TABLE loanops.scenario_assumption (
 scenario_id UUID PRIMARY KEY REFERENCES loanops.scenario(id),
 payload_ciphertext TEXT NOT NULL
);
CREATE TABLE loanops.scenario_result (
 scenario_id UUID PRIMARY KEY REFERENCES loanops.scenario(id),
 payload_hash VARCHAR(64) NOT NULL,
 payload_ciphertext TEXT NOT NULL
);
CREATE TRIGGER immutable_scenario BEFORE UPDATE OR DELETE ON loanops.scenario FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_scenario_assumption BEFORE UPDATE OR DELETE ON loanops.scenario_assumption FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_scenario_result BEFORE UPDATE OR DELETE ON loanops.scenario_result FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
