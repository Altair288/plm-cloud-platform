SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_code_sequence
    ADD COLUMN IF NOT EXISTS sub_rule_key VARCHAR(64);

ALTER TABLE plm_meta.meta_code_sequence
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(64);

ALTER TABLE plm_meta.meta_code_sequence
    ADD COLUMN IF NOT EXISTS scope_value VARCHAR(128);

ALTER TABLE plm_meta.meta_code_sequence
    ADD COLUMN IF NOT EXISTS reset_rule VARCHAR(32);

ALTER TABLE plm_meta.meta_code_sequence
    ADD COLUMN IF NOT EXISTS period_key VARCHAR(32);

UPDATE plm_meta.meta_code_sequence
SET sub_rule_key = COALESCE(NULLIF(btrim(sub_rule_key), ''), 'ROOT'),
    scope_key = COALESCE(NULLIF(btrim(scope_key), ''), 'GLOBAL'),
    scope_value = COALESCE(NULLIF(btrim(scope_value), ''), 'GLOBAL'),
    reset_rule = COALESCE(NULLIF(btrim(reset_rule), ''), 'NEVER'),
    period_key = COALESCE(NULLIF(btrim(period_key), ''), 'NONE');

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN sub_rule_key SET DEFAULT 'ROOT';

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN scope_key SET DEFAULT 'GLOBAL';

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN scope_value SET DEFAULT 'GLOBAL';

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN reset_rule SET DEFAULT 'NEVER';

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN period_key SET DEFAULT 'NONE';

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN sub_rule_key SET NOT NULL;

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN scope_key SET NOT NULL;

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN scope_value SET NOT NULL;

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN reset_rule SET NOT NULL;

ALTER TABLE plm_meta.meta_code_sequence
    ALTER COLUMN period_key SET NOT NULL;

ALTER TABLE plm_meta.meta_code_sequence
    DROP CONSTRAINT IF EXISTS meta_code_sequence_pkey;

ALTER TABLE plm_meta.meta_code_sequence
    ADD CONSTRAINT meta_code_sequence_pkey
        PRIMARY KEY (rule_code, sub_rule_key, scope_key, scope_value, period_key);

CREATE INDEX IF NOT EXISTS idx_meta_code_sequence_rule_code
    ON plm_meta.meta_code_sequence(rule_code);