-- =====================================================================
-- V14: 为版本表补充 status 字段，支持级联软删除标记
-- 目标：meta_attribute_def 删除时，可同步标记 attribute/lov 的所有版本记录
-- =====================================================================

SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_attribute_version
  ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'active';

ALTER TABLE plm_meta.meta_lov_version
  ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'active';

CREATE INDEX IF NOT EXISTS idx_attr_ver_status
  ON plm_meta.meta_attribute_version(status);

CREATE INDEX IF NOT EXISTS idx_lov_ver_status
  ON plm_meta.meta_lov_version(status);

-- ===================== END V14 =======================================
