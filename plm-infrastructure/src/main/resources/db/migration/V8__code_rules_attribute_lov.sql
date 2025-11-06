-- V8: Add code generation rules for ATTRIBUTE & LOV keys without sequence usage.
-- Pattern variables: {CATEGORY_CODE} normalized with underscores, {ATTR_INITIALS} uppercase pinyin abbreviation.
-- If rules already exist, do nothing.

INSERT INTO plm_meta.meta_code_rule (id, code, pattern, inherit_prefix, created_at)
SELECT gen_random_uuid(), 'ATTRIBUTE', '{CATEGORY_CODE}_{ATTR_INITIALS}', false, now()
WHERE NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_rule WHERE code = 'ATTRIBUTE');

INSERT INTO plm_meta.meta_code_rule (id, code, pattern, inherit_prefix, created_at)
SELECT gen_random_uuid(), 'LOV', '{CATEGORY_CODE}_{ATTR_INITIALS}__LOV', false, now()
WHERE NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_rule WHERE code = 'LOV');

-- No sequence rows needed because patterns don't contain {SEQ}.
