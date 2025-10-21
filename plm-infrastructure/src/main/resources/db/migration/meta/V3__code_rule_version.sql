-- V3__code_rule_version.sql
-- 新增编码规则版本表：将编码规则版本化，后续每次修改插入新版本行。
-- 若已有 meta_code_rule 中的记录，可在此脚本末尾初始化第一版版本数据。

SET search_path TO plm_meta;

CREATE TABLE IF NOT EXISTS plm_meta.meta_code_rule_version (
  id              UUID PRIMARY KEY,
  code_rule_id    UUID NOT NULL REFERENCES meta_code_rule(id) ON DELETE CASCADE,
  version_no      INT NOT NULL,
  rule_json       JSONB NOT NULL,      -- { pattern:"<categoryCode>ATT_<SEQ4>", length:4, step:1, inheritFrom:null }
  hash            VARCHAR(64),
  is_latest       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      VARCHAR(64),
  UNIQUE(code_rule_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_code_rule_version_latest ON meta_code_rule_version(code_rule_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_code_rule_version_hash   ON meta_code_rule_version(hash);

-- 初始化：为已有编码规则生成第一版版本记录（仅在空版本表时执行）
INSERT INTO meta_code_rule_version (id, code_rule_id, version_no, rule_json, is_latest, created_at)
SELECT gen_random_uuid(), r.id, 1,
       jsonb_build_object('pattern', expression, 'length', 0, 'step', 1, 'inheritFrom', inherit_from),
       TRUE, now()
FROM meta_code_rule r
LEFT JOIN meta_code_rule_version v ON v.code_rule_id = r.id
WHERE v.code_rule_id IS NULL;
