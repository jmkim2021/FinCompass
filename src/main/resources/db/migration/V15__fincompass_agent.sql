CREATE TABLE loanops.agent_run (
 id UUID PRIMARY KEY,
 financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
 started_at TIMESTAMPTZ NOT NULL,
 completed_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(24) NOT NULL,
 model_provider VARCHAR(80) NOT NULL,
 model_version VARCHAR(80) NOT NULL,
 financial_snapshot_id UUID REFERENCES loanops.financial_snapshot(id),
 scenario_id UUID REFERENCES loanops.scenario(id),
 product_match_run_id UUID REFERENCES loanops.product_match_run(id),
 query_hash VARCHAR(64) NOT NULL,
 payload_ciphertext TEXT NOT NULL
);

CREATE TABLE loanops.agent_tool_call (
 id UUID PRIMARY KEY,
 agent_run_id UUID NOT NULL REFERENCES loanops.agent_run(id),
 ordinal INTEGER NOT NULL,
 tool_name VARCHAR(80) NOT NULL,
 started_at TIMESTAMPTZ NOT NULL,
 completed_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(24) NOT NULL,
 params_hash VARCHAR(64) NOT NULL,
 result_hash VARCHAR(64),
 payload_ciphertext TEXT NOT NULL,
 UNIQUE(agent_run_id, ordinal)
);

CREATE INDEX agent_run_owner_started ON loanops.agent_run(financial_user_id, started_at DESC);
CREATE TRIGGER immutable_agent_run BEFORE UPDATE OR DELETE ON loanops.agent_run FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_agent_tool_call BEFORE UPDATE OR DELETE ON loanops.agent_tool_call FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
