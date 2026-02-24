-- =====================================================================
-- V13: meta_attribute_def 唯一性改造（软删可复用 key）
-- 目标：仅对未删除(status <> 'deleted')的数据强制(category_def_id, key)唯一
-- =====================================================================

SET search_path TO plm_meta, public;

DO $$
DECLARE
  rec RECORD;
BEGIN
  -- 1) 删除旧的“全量唯一约束”（包含 V1/V6 可能留下的约束）
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

  -- 2) 删除可能残留的同名索引（非必须，但可避免命名冲突）
  EXECUTE 'DROP INDEX IF EXISTS plm_meta.uk_meta_attribute_def_cat_key';
  EXECUTE 'DROP INDEX IF EXISTS plm_meta.uidx_meta_attribute_def_cat_key_active';

  -- 3) 创建“仅未删除唯一”的部分索引
  EXECUTE 'CREATE UNIQUE INDEX uidx_meta_attribute_def_cat_key_active '
       || 'ON plm_meta.meta_attribute_def(category_def_id, key) '
       || 'WHERE status <> ''deleted''';
END
$$;

-- ===================== END V13 =======================================
