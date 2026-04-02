SET search_path TO plm_meta, public;

CREATE TABLE IF NOT EXISTS plm_meta.meta_workbook_import_snapshot (
    id UUID PRIMARY KEY,
    import_session_id VARCHAR(64) NOT NULL,
    dry_run_job_id VARCHAR(64),
    operator_name VARCHAR(64),
    options_json JSONB NOT NULL,
    response_json JSONB NOT NULL,
    categories_json JSONB NOT NULL,
    attributes_json JSONB NOT NULL,
    enum_options_json JSONB NOT NULL,
    existing_data_json JSONB NOT NULL,
    execution_plan_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_meta_workbook_import_snapshot_session
    ON plm_meta.meta_workbook_import_snapshot(import_session_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_meta_workbook_import_snapshot_dry_run_job
    ON plm_meta.meta_workbook_import_snapshot(dry_run_job_id)
    WHERE dry_run_job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_meta_workbook_import_snapshot_expires_at
    ON plm_meta.meta_workbook_import_snapshot(expires_at);