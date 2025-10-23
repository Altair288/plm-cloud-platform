-- V4__rename_full_name_to_full_path_name.sql
-- 目的: 将 meta_category_def.full_name 重命名为 full_path_name 以消除语义歧义
-- 仅列重命名，不修改数据；如果列已不存在或已是新名称则幂等跳过。

SET search_path TO plm_meta, public;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'plm_meta' 
          AND table_name = 'meta_category_def' 
          AND column_name = 'full_name'
    ) THEN
        ALTER TABLE plm_meta.meta_category_def RENAME COLUMN full_name TO full_path_name;
    END IF;
END $$;

-- 为新名称添加注释（可选）
COMMENT ON COLUMN plm_meta.meta_category_def.full_path_name IS '全路径显示名称（包含所有祖先名称拼接）';
