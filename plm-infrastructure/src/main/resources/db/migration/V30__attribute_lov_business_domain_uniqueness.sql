-- =====================================================================
-- V30: 属性/LOV 编码唯一性切换到 business_domain 维度
-- 目标：
-- 1) meta_attribute_def 在未删除数据上按 (business_domain, key) 唯一
-- 2) meta_lov_def 在未删除数据上按 (business_domain, key) 唯一
-- =====================================================================

SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_attribute_def
  ADD COLUMN IF NOT EXISTS business_domain VARCHAR(64);

UPDATE plm_meta.meta_attribute_def d
SET business_domain = c.business_domain
FROM plm_meta.meta_category_def c
WHERE d.category_def_id = c.id
  AND (d.business_domain IS NULL OR d.business_domain <> c.business_domain);

ALTER TABLE plm_meta.meta_attribute_def
  ALTER COLUMN business_domain SET NOT NULL;

DO $$
DECLARE
  rec RECORD;
BEGIN
  FOR rec IN
    SELECT con.conname
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    JOIN pg_attribute a1 ON a1.attrelid = rel.oid AND a1.attnum = con.conkey[1]
    JOIN pg_attribute a2 ON a2.attrelid = rel.oid AND a2.attnum = con.conkey[2]
    WHERE con.contype = 'u'
      AND nsp.nspname = 'plm_meta'
      AND rel.relname = 'meta_attribute_def'
      AND array_length(con.conkey, 1) = 2
      AND ((a1.attname = 'category_def_id' AND a2.attname = 'key')
        OR (a1.attname = 'key' AND a2.attname = 'category_def_id'))
  LOOP
    EXECUTE format('ALTER TABLE plm_meta.meta_attribute_def DROP CONSTRAINT IF EXISTS %I', rec.conname);
  END LOOP;
END
$$;

DROP INDEX IF EXISTS plm_meta.uk_meta_attribute_def_cat_key;
DROP INDEX IF EXISTS plm_meta.uidx_meta_attribute_def_cat_key_active;
DROP INDEX IF EXISTS plm_meta.uidx_meta_attribute_def_bd_key_active;

CREATE UNIQUE INDEX uidx_meta_attribute_def_bd_key_active
  ON plm_meta.meta_attribute_def(business_domain, key)
  WHERE status <> 'deleted';

CREATE INDEX IF NOT EXISTS idx_meta_attribute_def_bd_key
  ON plm_meta.meta_attribute_def(business_domain, key);

ALTER TABLE plm_meta.meta_lov_def
  ADD COLUMN IF NOT EXISTS business_domain VARCHAR(64);

UPDATE plm_meta.meta_lov_def d
SET business_domain = a.business_domain
FROM plm_meta.meta_attribute_def a
WHERE d.attribute_def_id = a.id
  AND (d.business_domain IS NULL OR d.business_domain <> a.business_domain);

ALTER TABLE plm_meta.meta_lov_def
  ALTER COLUMN business_domain SET NOT NULL;

DO $$
DECLARE
  rec RECORD;
BEGIN
  FOR rec IN
    SELECT con.conname
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    JOIN pg_attribute a1 ON a1.attrelid = rel.oid AND a1.attnum = con.conkey[1]
    JOIN pg_attribute a2 ON a2.attrelid = rel.oid AND a2.attnum = con.conkey[2]
    WHERE con.contype = 'u'
      AND nsp.nspname = 'plm_meta'
      AND rel.relname = 'meta_lov_def'
      AND array_length(con.conkey, 1) = 2
      AND ((a1.attname = 'attribute_def_id' AND a2.attname = 'key')
        OR (a1.attname = 'key' AND a2.attname = 'attribute_def_id'))
  LOOP
    EXECUTE format('ALTER TABLE plm_meta.meta_lov_def DROP CONSTRAINT IF EXISTS %I', rec.conname);
  END LOOP;
END
$$;

DROP INDEX IF EXISTS plm_meta.uk_meta_lov_def_attr_key;
DROP INDEX IF EXISTS plm_meta.uidx_meta_lov_def_bd_key_active;

CREATE UNIQUE INDEX uidx_meta_lov_def_bd_key_active
  ON plm_meta.meta_lov_def(business_domain, key)
  WHERE status <> 'deleted';

CREATE INDEX IF NOT EXISTS idx_meta_lov_def_bd_key
  ON plm_meta.meta_lov_def(business_domain, key);

-- 迁移后建议人工核验：
-- select business_domain, key, count(*) from plm_meta.meta_attribute_def where status <> 'deleted' group by 1,2 having count(*) > 1;
-- select business_domain, key, count(*) from plm_meta.meta_lov_def where status <> 'deleted' group by 1,2 having count(*) > 1;