SET search_path TO plm_meta, public;

UPDATE plm_meta.meta_code_rule_version
SET hash = md5(COALESCE(rule_json::text, '{}'))
WHERE hash IS DISTINCT FROM md5(COALESCE(rule_json::text, '{}'));