CREATE TABLE loanops.product_match_run (
 id UUID PRIMARY KEY, financial_user_id UUID NOT NULL REFERENCES loanops.financial_users(id),
 financial_snapshot_id UUID NOT NULL REFERENCES loanops.financial_snapshot(id), scenario_id UUID REFERENCES loanops.scenario(id),
 created_at TIMESTAMPTZ NOT NULL, calculation_rule_version VARCHAR(80) NOT NULL,
 simulation_rule_version VARCHAR(80) NOT NULL, score_rule_version VARCHAR(80) NOT NULL, rate_policy_version VARCHAR(80) NOT NULL
);
CREATE INDEX product_match_owner_history ON loanops.product_match_run(financial_user_id,created_at DESC,id);
CREATE TABLE loanops.product_match_payload (
 match_run_id UUID PRIMARY KEY REFERENCES loanops.product_match_run(id), payload_hash VARCHAR(64) NOT NULL,
 payload_ciphertext TEXT NOT NULL
);
CREATE TABLE loanops.product_match_candidate (
 match_run_id UUID NOT NULL REFERENCES loanops.product_match_run(id), product_term_id BIGINT NOT NULL,
 loan_product_id BIGINT NOT NULL, term_parent_product_id BIGINT NOT NULL,
 product_publication_run_id BIGINT NOT NULL, term_publication_run_id BIGINT NOT NULL,
 product_source_run_id BIGINT NOT NULL, term_source_run_id BIGINT NOT NULL,
 product_raw_snapshot_id BIGINT NOT NULL, term_raw_snapshot_id BIGINT NOT NULL,
 product_raw_hash VARCHAR(64) NOT NULL, term_raw_hash VARCHAR(64) NOT NULL,
 status VARCHAR(32) NOT NULL CHECK(status IN ('MATCHED_FOR_REVIEW','FILTERED_OUT','INSUFFICIENT_DATA')),
 simulation_rate NUMERIC(18,16), score NUMERIC(9,4), review_rank INTEGER,
 PRIMARY KEY(match_run_id,product_term_id)
);
CREATE TABLE loanops.candidate_score_component (
 match_run_id UUID NOT NULL, product_term_id BIGINT NOT NULL, name VARCHAR(50) NOT NULL,
 raw_score NUMERIC(9,4) NOT NULL, weighted_score NUMERIC(9,4) NOT NULL,
 PRIMARY KEY(match_run_id,product_term_id,name),
 FOREIGN KEY(match_run_id,product_term_id) REFERENCES loanops.product_match_candidate(match_run_id,product_term_id)
);
CREATE TABLE loanops.candidate_scenario_result (
 match_run_id UUID NOT NULL, product_term_id BIGINT NOT NULL, payload_ciphertext TEXT NOT NULL,
 PRIMARY KEY(match_run_id,product_term_id),
 FOREIGN KEY(match_run_id,product_term_id) REFERENCES loanops.product_match_candidate(match_run_id,product_term_id)
);
CREATE TRIGGER immutable_product_match_run BEFORE UPDATE OR DELETE ON loanops.product_match_run FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_product_match_payload BEFORE UPDATE OR DELETE ON loanops.product_match_payload FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_product_match_candidate BEFORE UPDATE OR DELETE ON loanops.product_match_candidate FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_candidate_score BEFORE UPDATE OR DELETE ON loanops.candidate_score_component FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
CREATE TRIGGER immutable_candidate_scenario BEFORE UPDATE OR DELETE ON loanops.candidate_scenario_result FOR EACH ROW EXECUTE FUNCTION loanops.reject_financial_history_change();
