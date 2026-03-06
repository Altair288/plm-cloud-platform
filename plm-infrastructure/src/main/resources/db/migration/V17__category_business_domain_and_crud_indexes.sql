-- =====================================================================
-- V17: 分类主标识切换为 (business_domain, code_key) 并补充 CRUD 查询索引
-- =====================================================================

SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS business_domain VARCHAR(64);

-- 历史数据回填：默认归入 MATERIAL 领域
UPDATE plm_meta.meta_category_def
SET business_domain = 'MATERIAL'
WHERE business_domain IS NULL OR btrim(business_domain) = '';

ALTER TABLE plm_meta.meta_category_def
  ALTER COLUMN business_domain SET NOT NULL;

-- 兼容不同环境下的唯一约束命名，清理 code_key 全局唯一
DO $$
DECLARE
  con_name text;
BEGIN
  SELECT c.conname
  INTO con_name
  FROM pg_constraint c
  JOIN pg_class t ON t.oid = c.conrelid
  JOIN pg_namespace n ON n.oid = t.relnamespace
  WHERE n.nspname = 'plm_meta'
    AND t.relname = 'meta_category_def'
    AND c.contype = 'u'
    AND pg_get_constraintdef(c.oid) ILIKE '%(code_key)%'
    AND pg_get_constraintdef(c.oid) NOT ILIKE '%business_domain%'
  LIMIT 1;

  IF con_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE plm_meta.meta_category_def DROP CONSTRAINT %I', con_name);
  END IF;
END
$$;

ALTER TABLE plm_meta.meta_category_def
  ADD CONSTRAINT uq_meta_category_def_business_domain_code
  UNIQUE (business_domain, code_key);

CREATE INDEX IF NOT EXISTS idx_meta_cat_def_domain_parent_status_sort
  ON plm_meta.meta_category_def(business_domain, parent_def_id, lower(status), sort_order, code_key);

CREATE INDEX IF NOT EXISTS idx_meta_cat_def_domain_code
  ON plm_meta.meta_category_def(business_domain, code_key);

-- ===================== END V17 =======================================
