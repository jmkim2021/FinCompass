ALTER TABLE loanops.raw_snapshots
    DROP CONSTRAINT raw_snapshots_source_run_id_key;

CREATE INDEX raw_snapshots_source_run_id_idx
    ON loanops.raw_snapshots (source_run_id, id);
