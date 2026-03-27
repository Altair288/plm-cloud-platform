SET search_path TO plm_meta, public;

UPDATE plm_meta.meta_code_rule
SET pattern = 'ATTR-{CATEGORY_CODE}-{SEQ}',
    max_length = 128,
    regex_pattern = '^[A-Z][A-Z0-9_-]{0,127}$',
    updated_at = now(),
    updated_by = 'flyway-v27'
WHERE code = 'ATTRIBUTE';

UPDATE plm_meta.meta_code_rule
SET pattern = 'ENUM-{ATTRIBUTE_CODE}-{SEQ}',
    max_length = 128,
    regex_pattern = '^[A-Z][A-Z0-9_-]{0,127}$',
    updated_at = now(),
    updated_by = 'flyway-v27'
WHERE code = 'LOV';

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', 'ATTR-{CATEGORY_CODE}-{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'attribute', jsonb_build_object(
                'separator', '-',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'STRING', 'value', 'ATTR'),
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'CATEGORY_CODE'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 6, 'startValue', 1, 'step', 1, 'resetRule', 'PER_PARENT', 'scopeKey', 'CATEGORY_CODE')
                ),
                'allowedVariableKeys', jsonb_build_array('BUSINESS_DOMAIN', 'CATEGORY_CODE')
            )
        ),
        'validation', jsonb_build_object('maxLength', 128, 'regex', '^[A-Z][A-Z0-9_-]{0,127}$', 'allowManualOverride', true)
    ),
    hash = md5(jsonb_build_object(
        'pattern', 'ATTR-{CATEGORY_CODE}-{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'attribute', jsonb_build_object(
                'separator', '-',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'STRING', 'value', 'ATTR'),
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'CATEGORY_CODE'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 6, 'startValue', 1, 'step', 1, 'resetRule', 'PER_PARENT', 'scopeKey', 'CATEGORY_CODE')
                ),
                'allowedVariableKeys', jsonb_build_array('BUSINESS_DOMAIN', 'CATEGORY_CODE')
            )
        ),
        'validation', jsonb_build_object('maxLength', 128, 'regex', '^[A-Z][A-Z0-9_-]{0,127}$', 'allowManualOverride', true)
    )::text),
    created_by = coalesce(v.created_by, 'flyway-v27')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'ATTRIBUTE'
  AND v.is_latest = TRUE;

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', 'ENUM-{ATTRIBUTE_CODE}-{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'enum', jsonb_build_object(
                'separator', '-',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'STRING', 'value', 'ENUM'),
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'ATTRIBUTE_CODE'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 2, 'startValue', 1, 'step', 1, 'resetRule', 'PER_PARENT', 'scopeKey', 'ATTRIBUTE_CODE')
                ),
                'allowedVariableKeys', jsonb_build_array('ATTRIBUTE_CODE', 'CATEGORY_CODE', 'BUSINESS_DOMAIN')
            )
        ),
        'validation', jsonb_build_object('maxLength', 128, 'regex', '^[A-Z][A-Z0-9_-]{0,127}$', 'allowManualOverride', true)
    ),
    hash = md5(jsonb_build_object(
        'pattern', 'ENUM-{ATTRIBUTE_CODE}-{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'enum', jsonb_build_object(
                'separator', '-',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'STRING', 'value', 'ENUM'),
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'ATTRIBUTE_CODE'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 2, 'startValue', 1, 'step', 1, 'resetRule', 'PER_PARENT', 'scopeKey', 'ATTRIBUTE_CODE')
                ),
                'allowedVariableKeys', jsonb_build_array('ATTRIBUTE_CODE', 'CATEGORY_CODE', 'BUSINESS_DOMAIN')
            )
        ),
        'validation', jsonb_build_object('maxLength', 128, 'regex', '^[A-Z][A-Z0-9_-]{0,127}$', 'allowManualOverride', true)
    )::text),
    created_by = coalesce(v.created_by, 'flyway-v27')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'LOV'
  AND v.is_latest = TRUE;