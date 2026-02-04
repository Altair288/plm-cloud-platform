-- =====================================================================
-- V11: 为 meta_attribute_version 增加生成列（从 structure_json 投影）+ 索引
-- 目的：支持属性列表的大规模过滤（名称搜索 / dataType / required / unique / searchable）
-- =====================================================================

SET search_path TO plm_meta, public;

-- 1) 尝试安装 pg_trgm（用于 ILIKE '%keyword%' 的高性能索引）
DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION
  WHEN insufficient_privilege THEN
    RAISE NOTICE 'skip CREATE EXTENSION pg_trgm: insufficient privilege';
END
$$;

-- 2) 在 meta_attribute_version 上增加生成列（仅用于查询/索引，写入仍以 structure_json 为准）
ALTER TABLE plm_meta.meta_attribute_version
  ADD COLUMN IF NOT EXISTS display_name TEXT
    GENERATED ALWAYS AS ((structure_json->>'displayName')) STORED,
  ADD COLUMN IF NOT EXISTS data_type VARCHAR(64)
    GENERATED ALWAYS AS ((structure_json->>'dataType')) STORED,
  ADD COLUMN IF NOT EXISTS unit VARCHAR(32)
    GENERATED ALWAYS AS ((structure_json->>'unit')) STORED,
  ADD COLUMN IF NOT EXISTS lov_key VARCHAR(128)
    GENERATED ALWAYS AS ((structure_json->>'lovKey')) STORED,
  ADD COLUMN IF NOT EXISTS required_flag BOOLEAN
    GENERATED ALWAYS AS (COALESCE((structure_json->>'required')::boolean, FALSE)) STORED,
  ADD COLUMN IF NOT EXISTS unique_flag BOOLEAN
    GENERATED ALWAYS AS (COALESCE((structure_json->>'unique')::boolean, FALSE)) STORED,
  ADD COLUMN IF NOT EXISTS searchable_flag BOOLEAN
    GENERATED ALWAYS AS (COALESCE((structure_json->>'searchable')::boolean, FALSE)) STORED;

-- 3) 索引：左侧列表高频路径（按分类版本 + latest）
CREATE INDEX IF NOT EXISTS idx_attr_ver_catver_latest
  ON plm_meta.meta_attribute_version(category_version_id, is_latest);

-- 4) 索引：过滤字段（通过 bitmap index scan 组合）
CREATE INDEX IF NOT EXISTS idx_attr_ver_latest_datatype
  ON plm_meta.meta_attribute_version(category_version_id, is_latest, data_type);

CREATE INDEX IF NOT EXISTS idx_attr_ver_latest_required
  ON plm_meta.meta_attribute_version(category_version_id, is_latest, required_flag);

CREATE INDEX IF NOT EXISTS idx_attr_ver_latest_unique
  ON plm_meta.meta_attribute_version(category_version_id, is_latest, unique_flag);

CREATE INDEX IF NOT EXISTS idx_attr_ver_latest_searchable
  ON plm_meta.meta_attribute_version(category_version_id, is_latest, searchable_flag);

-- 5) 名称模糊搜索索引（可选：依赖 pg_trgm）
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_attr_ver_display_name_trgm ON plm_meta.meta_attribute_version USING gin (display_name gin_trgm_ops)';
  ELSE
    RAISE NOTICE 'pg_trgm not installed, skip trgm index for display_name';
  END IF;
END
$$;

-- ===================== END V11 =======================================
