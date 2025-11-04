-- =============================================================
-- V6: Attribute & LOV 去重唯一性约束
-- 目的：确保同一分类下属性 key 唯一；同一属性下 LOV key 唯一；
--      以及全局 lov_def.key 可选（保留灵活性，不做全局唯一约束）。
-- =============================================================

-- 1. meta_attribute_def(category_def_id, key)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON c.conrelid = t.oid
        WHERE c.contype = 'u'
          AND t.relname = 'meta_attribute_def'
          AND c.conname = 'uk_meta_attribute_def_cat_key'
    ) THEN
        ALTER TABLE plm_meta.meta_attribute_def
            ADD CONSTRAINT uk_meta_attribute_def_cat_key UNIQUE (category_def_id, key);
    END IF;
END $$;

-- 2. meta_lov_def(attribute_def_id, key)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON c.conrelid = t.oid
        WHERE c.contype = 'u'
          AND t.relname = 'meta_lov_def'
          AND c.conname = 'uk_meta_lov_def_attr_key'
    ) THEN
        ALTER TABLE plm_meta.meta_lov_def
            ADD CONSTRAINT uk_meta_lov_def_attr_key UNIQUE (attribute_def_id, key);
    END IF;
END $$;

-- 3. 建议性索引：按 attribute_def_id 查询最新 LOV Def
CREATE INDEX IF NOT EXISTS idx_meta_lov_def_attr ON plm_meta.meta_lov_def(attribute_def_id);

-- 4. 备注：不对 lov_def.key 做全局唯一约束，允许不同属性生成相同可读 key 场景。

-- ============= END V6 =========================================