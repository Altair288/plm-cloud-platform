SET search_path TO plm_meta, public;

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', '{BUSINESS_DOMAIN}-{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'category', jsonb_build_object(
                'separator', '-',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'BUSINESS_DOMAIN'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 4, 'startValue', 1, 'step', 1, 'resetRule', 'NEVER', 'scopeKey', 'GLOBAL')
                ),
                'allowedVariableKeys', jsonb_build_array('BUSINESS_DOMAIN', 'PARENT_CODE')
            )
        ),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true)
    ),
    hash = md5(jsonb_build_object(
        'pattern', '{BUSINESS_DOMAIN}-{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'category', jsonb_build_object(
                'separator', '-',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'BUSINESS_DOMAIN'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 4, 'startValue', 1, 'step', 1, 'resetRule', 'NEVER', 'scopeKey', 'GLOBAL')
                ),
                'allowedVariableKeys', jsonb_build_array('BUSINESS_DOMAIN', 'PARENT_CODE')
            )
        ),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true)
    )::text),
    created_by = coalesce(v.created_by, 'flyway-v26')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'CATEGORY'
  AND v.is_latest = TRUE;

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', 'ATTR_{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'attribute', jsonb_build_object(
                'separator', '_',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'STRING', 'value', 'ATTR'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 6, 'startValue', 1, 'step', 1, 'resetRule', 'NEVER', 'scopeKey', 'GLOBAL')
                ),
                'allowedVariableKeys', jsonb_build_array('BUSINESS_DOMAIN', 'CATEGORY_CODE')
            )
        ),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true)
    ),
    hash = md5(jsonb_build_object(
        'pattern', 'ATTR_{SEQ}',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'attribute', jsonb_build_object(
                'separator', '_',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'STRING', 'value', 'ATTR'),
                    jsonb_build_object('type', 'SEQUENCE', 'length', 6, 'startValue', 1, 'step', 1, 'resetRule', 'NEVER', 'scopeKey', 'GLOBAL')
                ),
                'allowedVariableKeys', jsonb_build_array('BUSINESS_DOMAIN', 'CATEGORY_CODE')
            )
        ),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true)
    )::text),
    created_by = coalesce(v.created_by, 'flyway-v26')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'ATTRIBUTE'
  AND v.is_latest = TRUE;

UPDATE plm_meta.meta_code_rule_version v
SET rule_json = jsonb_build_object(
        'pattern', '{ATTRIBUTE_CODE}_LOV',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'enum', jsonb_build_object(
                'separator', '_',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'ATTRIBUTE_CODE'),
                    jsonb_build_object('type', 'STRING', 'value', 'LOV')
                ),
                'allowedVariableKeys', jsonb_build_array('ATTRIBUTE_CODE', 'CATEGORY_CODE', 'BUSINESS_DOMAIN')
            )
        ),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true)
    ),
    hash = md5(jsonb_build_object(
        'pattern', '{ATTRIBUTE_CODE}_LOV',
        'hierarchyMode', 'NONE',
        'subRules', jsonb_build_object(
            'enum', jsonb_build_object(
                'separator', '_',
                'segments', jsonb_build_array(
                    jsonb_build_object('type', 'VARIABLE', 'variableKey', 'ATTRIBUTE_CODE'),
                    jsonb_build_object('type', 'STRING', 'value', 'LOV')
                ),
                'allowedVariableKeys', jsonb_build_array('ATTRIBUTE_CODE', 'CATEGORY_CODE', 'BUSINESS_DOMAIN')
            )
        ),
        'validation', jsonb_build_object('maxLength', 64, 'regex', '^[A-Z][A-Z0-9_-]{0,63}$', 'allowManualOverride', true)
    )::text),
    created_by = coalesce(v.created_by, 'flyway-v26')
FROM plm_meta.meta_code_rule r
WHERE v.code_rule_id = r.id
  AND r.code = 'LOV'
  AND v.is_latest = TRUE;