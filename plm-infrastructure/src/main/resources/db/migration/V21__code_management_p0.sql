SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS scope_type VARCHAR(32) NOT NULL DEFAULT 'GLOBAL';

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS scope_value VARCHAR(128);

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS allow_manual_override BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS regex_pattern VARCHAR(255);

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS max_length INT NOT NULL DEFAULT 64;

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);

UPDATE plm_meta.meta_code_rule
SET status = CASE
    WHEN active IS TRUE THEN 'ACTIVE'
    ELSE 'DRAFT'
END
WHERE status IS NULL OR btrim(status) = '';

UPDATE plm_meta.meta_code_rule
SET regex_pattern = '^[A-Z][A-Z0-9_-]{0,63}$'
WHERE regex_pattern IS NULL AND code IN ('CATEGORY', 'ATTRIBUTE', 'LOV', 'INSTANCE');

UPDATE plm_meta.meta_code_rule
SET allow_manual_override = TRUE,
    pattern = '{BUSINESS_DOMAIN}-{SEQ}',
    max_length = 64,
    regex_pattern = '^[A-Z][A-Z0-9_-]{0,63}$',
    status = 'ACTIVE',
    active = TRUE,
    updated_at = now(),
    updated_by = 'flyway-v21'
WHERE code = 'CATEGORY';

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', '{BUSINESS_DOMAIN}-{SEQ}',
        'tokens', jsonb_build_array('BUSINESS_DOMAIN', 'SEQ'),
        'sequence', jsonb_build_object('enabled', true, 'width', 4, 'step', 1),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true),
        'preview', jsonb_build_object('BUSINESS_DOMAIN', 'MATERIAL')
    ),
    hash = md5('{BUSINESS_DOMAIN}-{SEQ}:CATEGORY:P0'),
    created_by = coalesce(v.created_by, 'flyway-v21')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'CATEGORY'
  AND v.is_latest = TRUE;

CREATE TABLE IF NOT EXISTS plm_meta.meta_code_generation_audit (
    id UUID PRIMARY KEY,
    rule_code VARCHAR(64) NOT NULL,
    rule_version_no INT NOT NULL,
    generated_code VARCHAR(128) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id UUID,
    context_json JSONB,
    manual_override_flag BOOLEAN NOT NULL DEFAULT FALSE,
    frozen_flag BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_meta_code_generation_audit_rule_code
  ON plm_meta.meta_code_generation_audit(rule_code, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_meta_code_generation_audit_target
  ON plm_meta.meta_code_generation_audit(target_type, target_id);

ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS code_key_manual_override BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS code_key_frozen BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS generated_rule_code VARCHAR(64);

ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS generated_rule_version_no INT;

ALTER TABLE plm_meta.meta_attribute_def
  ADD COLUMN IF NOT EXISTS key_manual_override BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plm_meta.meta_attribute_def
  ADD COLUMN IF NOT EXISTS key_frozen BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plm_meta.meta_attribute_def
  ADD COLUMN IF NOT EXISTS generated_rule_code VARCHAR(64);

ALTER TABLE plm_meta.meta_attribute_def
  ADD COLUMN IF NOT EXISTS generated_rule_version_no INT;

ALTER TABLE plm_meta.meta_lov_def
  ADD COLUMN IF NOT EXISTS key_manual_override BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plm_meta.meta_lov_def
  ADD COLUMN IF NOT EXISTS key_frozen BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE plm_meta.meta_lov_def
  ADD COLUMN IF NOT EXISTS generated_rule_code VARCHAR(64);

ALTER TABLE plm_meta.meta_lov_def
  ADD COLUMN IF NOT EXISTS generated_rule_version_no INT;

INSERT INTO plm_meta.meta_code_sequence(rule_code, current_value)
SELECT 'CATEGORY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM plm_meta.meta_code_sequence WHERE rule_code = 'CATEGORY'
);
