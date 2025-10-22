-- V2__extend_code_rule_inheritance.sql
-- 目的：为编码规则(meta_code_rule)增加继承能力与自动前缀控制。
-- 变更点：
--   1. 新增列 parent_rule_id (自引用外键，允许多层级，如 ATTRIBUTE 继承 CATEGORY)
--   2. 新增列 inherit_prefix (BOOLEAN) 控制是否自动拼接父级 code
--   3. 调整已有 CATEGORY 规则的 pattern → CAT-{SEQ}
--   4. 为 ATTRIBUTE / LOV / INSTANCE 建立或更新继承关系与 pattern
--   5. 补充缺失的 LOV / INSTANCE 规则及对应序列表记录
-- 说明：暂不修改 CodeRuleGenerator 行为；后续可扩展在生成时按 inherit_prefix 拼接父级编码或解析 {CATEGORY_CODE}/{ATTRIBUTE_CODE} 占位符。

SET search_path TO plm_meta, public;

-- 1. 新增列（幂等）
ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS parent_rule_id UUID NULL REFERENCES plm_meta.meta_code_rule(id) ON DELETE SET NULL;

ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS inherit_prefix BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. 索引（便于按父级查询）
CREATE INDEX IF NOT EXISTS idx_meta_code_rule_parent ON plm_meta.meta_code_rule(parent_rule_id);

-- 3. 调整 CATEGORY pattern (CAT-{DATE}-{SEQ} → CAT-{SEQ})
UPDATE plm_meta.meta_code_rule SET pattern = 'CAT-{SEQ}' WHERE code = 'CATEGORY';

-- 4. 更新 ATTRIBUTE 继承 CATEGORY
UPDATE plm_meta.meta_code_rule a
SET parent_rule_id = c.id, inherit_prefix = TRUE, pattern = '{CATEGORY_CODE}-ATT-{SEQ}'
FROM plm_meta.meta_code_rule c
WHERE a.code = 'ATTRIBUTE' AND c.code = 'CATEGORY';

-- 5. 插入 LOV 规则（继承 ATTRIBUTE）
INSERT INTO plm_meta.meta_code_rule (id, code, name, target_type, pattern, inherit_prefix, parent_rule_id, active, created_at)
SELECT gen_random_uuid(), 'LOV', '枚举值编码规则', 'lov', '{ATTRIBUTE_CODE}-VAL-{SEQ}', TRUE, a.id, TRUE, now()
FROM plm_meta.meta_code_rule a
WHERE a.code = 'ATTRIBUTE'
  AND NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_rule WHERE code = 'LOV');

-- 6. 插入 INSTANCE 规则（继承 CATEGORY，是否拼接前缀由 pattern 自行包含 category code 占位，不再重复 inherit_prefix）
INSERT INTO plm_meta.meta_code_rule (id, code, name, target_type, pattern, inherit_prefix, parent_rule_id, active, created_at)
SELECT gen_random_uuid(), 'INSTANCE', '实例编码规则', 'instance', '{CATEGORY_CODE}-{DATE}-{SEQ}', FALSE, c.id, TRUE, now()
FROM plm_meta.meta_code_rule c
WHERE c.code = 'CATEGORY'
  AND NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_rule WHERE code = 'INSTANCE');

-- 7. 为新增规则补充序列表记录（LOV / INSTANCE）
INSERT INTO plm_meta.meta_code_sequence (rule_code, current_value)
  SELECT 'LOV', 0 WHERE NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_sequence WHERE rule_code = 'LOV');
INSERT INTO plm_meta.meta_code_sequence (rule_code, current_value)
  SELECT 'INSTANCE', 0 WHERE NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_sequence WHERE rule_code = 'INSTANCE');

-- 8. 初始化版本表: 对新增规则建立首版版本记录（如果还没有）
INSERT INTO plm_meta.meta_code_rule_version (id, code_rule_id, version_no, rule_json, is_latest, created_at)
SELECT gen_random_uuid(), r.id, 1,
       jsonb_build_object('pattern', r.pattern, 'step', 1, 'inheritFrom', NULL),
       TRUE, now()
FROM plm_meta.meta_code_rule r
LEFT JOIN plm_meta.meta_code_rule_version v ON v.code_rule_id = r.id
WHERE v.code_rule_id IS NULL
  AND r.code IN ('LOV','INSTANCE');

-- 9. 说明：后续扩展 CodeRuleGenerator 时可读取 parent_rule_id & inherit_prefix：
--    * 若 inherit_prefix=TRUE 且存在 parent，则在子规则生成时预先查询父对象已生成的 code 作为前缀。
--    * 若 pattern 中包含 {CATEGORY_CODE}/{ATTRIBUTE_CODE} 等占位符，需在调用层传入上下文 map。
--    * 当前实现不破坏旧逻辑；未使用的占位符保持原样。

-- ============ 结束 V2 ============