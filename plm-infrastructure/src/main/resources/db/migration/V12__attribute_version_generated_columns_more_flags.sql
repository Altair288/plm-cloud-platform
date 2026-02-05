-- =====================================================================
-- V12: 为 meta_attribute_version 增加更多 flags 的生成列（从 structure_json 投影）
-- 目的：属性列表返回 required/unique/hidden/readOnly/searchable 等配置字段
-- =====================================================================

SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_attribute_version
  ADD COLUMN IF NOT EXISTS hidden_flag BOOLEAN
    GENERATED ALWAYS AS (COALESCE((structure_json->>'hidden')::boolean, FALSE)) STORED,
  ADD COLUMN IF NOT EXISTS read_only_flag BOOLEAN
    GENERATED ALWAYS AS (COALESCE((structure_json->>'readOnly')::boolean, FALSE)) STORED;

-- 可选：如未来需要按 hidden/readOnly 过滤，可加索引（先预置，成本低）
CREATE INDEX IF NOT EXISTS idx_attr_ver_latest_hidden
  ON plm_meta.meta_attribute_version(category_version_id, is_latest, hidden_flag);

CREATE INDEX IF NOT EXISTS idx_attr_ver_latest_read_only
  ON plm_meta.meta_attribute_version(category_version_id, is_latest, read_only_flag);

-- ===================== END V12 =======================================
