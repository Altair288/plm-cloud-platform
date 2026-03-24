SET search_path TO plm_meta, public;

UPDATE plm_meta.meta_code_rule
SET inherit_prefix = FALSE,
    parent_rule_id = NULL,
    updated_at = now(),
    updated_by = 'flyway-v23'
WHERE code IN ('ATTRIBUTE', 'LOV');