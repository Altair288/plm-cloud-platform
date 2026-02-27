-- =====================================================================
-- V15: 为 meta_attribute_version 增加 attributeField 生成列 + 索引
-- 目的：列表接口直接返回 attributeField，并支持快速查询
-- =====================================================================

SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_attribute_version
  ADD COLUMN IF NOT EXISTS attribute_field VARCHAR(128)
    -- attributeField: 业务字段名，用于前后端字段映射 (business field name for frontend-backend mapping)
    GENERATED ALWAYS AS ((structure_json->>'attributeField')) STORED;

CREATE INDEX IF NOT EXISTS idx_attr_ver_latest_attribute_field
  ON plm_meta.meta_attribute_version(category_version_id, is_latest, attribute_field);

-- ===================== END V15 =======================================
