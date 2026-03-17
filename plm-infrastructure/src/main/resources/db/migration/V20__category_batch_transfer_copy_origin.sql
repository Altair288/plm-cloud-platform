-- =====================================================================
-- V20: 分类批量复制来源字段
-- =====================================================================

SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS copied_from_category_id UUID;

CREATE INDEX IF NOT EXISTS idx_meta_cat_def_copied_from
  ON plm_meta.meta_category_def(copied_from_category_id);

-- ===================== END V20 =======================================