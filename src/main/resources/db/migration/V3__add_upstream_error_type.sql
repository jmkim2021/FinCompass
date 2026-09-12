ALTER TABLE loanops.source_runs
    DROP CONSTRAINT source_runs_error_type_check;

ALTER TABLE loanops.source_runs
    ADD CONSTRAINT source_runs_error_type_check
        CHECK (error_type IS NULL OR error_type IN (
            'HTTP_ERROR',
            'UPSTREAM_ERROR',
            'TIMEOUT',
            'CONNECTION_ERROR',
            'UNKNOWN_ERROR'
        ));
