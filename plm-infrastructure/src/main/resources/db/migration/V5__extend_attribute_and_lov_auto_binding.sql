-- =====================================================================
-- V5: 扩展字段以支持自动枚举绑定 + 分类层级导入 + 编码规则分级
-- =====================================================================

-- ===================== 1. meta_attribute_def ==========================
-- 目的：区分属性是否枚举型，以及为自动匹配LOV提供关键字。
ALTER TABLE plm_meta.meta_attribute_def
  ADD COLUMN IF NOT EXISTS lov_flag BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS auto_bind_key VARCHAR(128);

COMMENT ON COLUMN plm_meta.meta_attribute_def.lov_flag IS '是否为枚举型属性';
COMMENT ON COLUMN plm_meta.meta_attribute_def.auto_bind_key IS '自动绑定枚举关键字，用于Excel导入自动识别LOV';

-- ===================== 2. meta_lov_def ================================
-- 目的：支持从Excel自动生成LOV并绑定属性。
ALTER TABLE plm_meta.meta_lov_def
  ADD COLUMN IF NOT EXISTS source_attribute_key VARCHAR(128),
  ADD COLUMN IF NOT EXISTS description TEXT;

COMMENT ON COLUMN plm_meta.meta_lov_def.source_attribute_key IS '源属性关键字，用于追踪该LOV由哪个属性生成';
COMMENT ON COLUMN plm_meta.meta_lov_def.description IS 'LOV用途说明';

-- ===================== 3. meta_category_def ===========================
-- 目的：支持从Excel导入层级分类（包含原始编号与层级）
ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS external_code VARCHAR(64),
  ADD COLUMN IF NOT EXISTS source_level SMALLINT;

COMMENT ON COLUMN plm_meta.meta_category_def.external_code IS 'Excel或外部来源的原始编号';
COMMENT ON COLUMN plm_meta.meta_category_def.source_level IS '分类层级编号';

-- ===================== 4. meta_code_rule ==============================
-- 目的：支持不同作用域（如分类级别、属性类型）下的编码规则。
ALTER TABLE plm_meta.meta_code_rule
  ADD COLUMN IF NOT EXISTS scope VARCHAR(64);

COMMENT ON COLUMN plm_meta.meta_code_rule.scope IS '编码规则作用范围';

-- ===================== 5. 索引优化（如需）
CREATE INDEX IF NOT EXISTS idx_meta_attribute_autobind
  ON plm_meta.meta_attribute_def (auto_bind_key);

CREATE INDEX IF NOT EXISTS idx_meta_lov_source_attr
  ON plm_meta.meta_lov_def (source_attribute_key);

-- ===================== 6. 元数据版本声明 =============================
INSERT INTO plm_meta.meta_code_rule (id, code, name, target_type, pattern, scope)
VALUES (gen_random_uuid(), 'ATTRIBUTE_ENUM', '枚举属性规则', 'attribute', 'LOV-{DATE}-{SEQ}', 'attribute_enum')
ON CONFLICT (code) DO NOTHING;

-- ===================== END ============================================
