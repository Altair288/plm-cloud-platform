SET search_path TO plm_meta, public;

ALTER TABLE IF EXISTS plm_meta.meta_workbook_import_snapshot
    ADD COLUMN IF NOT EXISTS staged_execution_plan_json JSONB;