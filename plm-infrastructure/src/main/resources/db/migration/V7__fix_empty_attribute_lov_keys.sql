-- V7: 清理空 key 并添加非空检查约束
-- 1. 添加检查约束，防止再次出现空 key
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_meta_attribute_def_key_nonempty'
    ) THEN
        ALTER TABLE plm_meta.meta_attribute_def
          ADD CONSTRAINT chk_meta_attribute_def_key_nonempty CHECK (length(trim(key)) > 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_meta_lov_def_key_nonempty'
    ) THEN
        ALTER TABLE plm_meta.meta_lov_def
          ADD CONSTRAINT chk_meta_lov_def_key_nonempty CHECK (length(trim(key)) > 0);
    END IF;
END $$;

-- 2. 备注：已存在唯一约束 (category_def_id,key) 现在 key 不可为空，可避免聚合误版本问题。