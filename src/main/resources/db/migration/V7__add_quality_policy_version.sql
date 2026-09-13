ALTER TABLE loanops.publication_runs
    ADD COLUMN quality_policy_version VARCHAR(32);

UPDATE loanops.publication_runs
SET quality_policy_version = '2026-09-v1';

ALTER TABLE loanops.publication_runs
    ALTER COLUMN quality_policy_version SET NOT NULL;
