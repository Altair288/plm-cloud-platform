SET search_path TO plm_meta, public;

UPDATE plm_meta.meta_code_rule
SET allow_manual_override = TRUE,
    pattern = 'ATTR_{SEQ}',
    max_length = 64,
    regex_pattern = '^[A-Z][A-Z0-9_-]{0,63}$',
    status = 'ACTIVE',
    active = TRUE,
    updated_at = now(),
    updated_by = 'flyway-v22'
WHERE code = 'ATTRIBUTE';

UPDATE plm_meta.meta_code_rule
SET allow_manual_override = TRUE,
    pattern = '{ATTRIBUTE_CODE}_LOV',
    max_length = 64,
    regex_pattern = '^[A-Z][A-Z0-9_-]{0,63}$',
    status = 'ACTIVE',
    active = TRUE,
    updated_at = now(),
    updated_by = 'flyway-v22'
WHERE code = 'LOV';

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', 'ATTR_{SEQ}',
        'tokens', jsonb_build_array('SEQ'),
        'sequence', jsonb_build_object('enabled', true, 'width', 6, 'step', 1),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true),
        'preview', jsonb_build_object('SEQ', '000001')
    ),
    hash = md5('ATTR_{SEQ}:ATTRIBUTE:V22'),
    created_by = coalesce(v.created_by, 'flyway-v22')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'ATTRIBUTE'
  AND v.is_latest = TRUE;

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', '{ATTRIBUTE_CODE}_LOV',
        'tokens', jsonb_build_array('ATTRIBUTE_CODE'),
        'sequence', jsonb_build_object('enabled', false, 'width', 2, 'step', 1),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true),
        'preview', jsonb_build_object('ATTRIBUTE_CODE', 'ATTR_000001')
    ),
    hash = md5('{ATTRIBUTE_CODE}_LOV:LOV:V22'),
    created_by = coalesce(v.created_by, 'flyway-v22')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'LOV'
  AND v.is_latest = TRUE;

INSERT INTO plm_meta.meta_code_sequence(rule_code, current_value)
SELECT 'ATTRIBUTE', 0
WHERE NOT EXISTS (
    SELECT 1 FROM plm_meta.meta_code_sequence WHERE rule_code = 'ATTRIBUTE'
);