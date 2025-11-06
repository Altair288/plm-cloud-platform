-- V9: Overwrite ATTRIBUTE / LOV patterns to pinyin initials based format (no sequence).
-- New patterns:
--   ATTRIBUTE -> {CATEGORY_CODE}_{ATTR_INITIALS}
--   LOV       -> {CATEGORY_CODE}_{ATTR_INITIALS}__LOV
-- Safe UPDATE (idempotent): only changes existing rows with matching code.

UPDATE plm_meta.meta_code_rule
   SET pattern = '{CATEGORY_CODE}_{ATTR_INITIALS}'
 WHERE code = 'ATTRIBUTE';

UPDATE plm_meta.meta_code_rule
   SET pattern = '{CATEGORY_CODE}_{ATTR_INITIALS}__LOV'
 WHERE code = 'LOV';

-- Optional cleanup: sequences no longer needed for these rules (uncomment if desired)
-- DELETE FROM plm_meta.meta_code_sequence WHERE rule_code IN ('ATTRIBUTE','LOV');
