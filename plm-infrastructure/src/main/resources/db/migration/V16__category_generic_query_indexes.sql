-- =====================================================================
-- V16: 通用分类查询索引完善（nodes/path/search/taxonomy）
-- 目标：匹配通用分类接口的高频查询路径，提升逐级加载、路径回显与关键词搜索性能
-- =====================================================================

SET search_path TO plm_meta, public;

-- 1) 尝试安装 pg_trgm（用于 ILIKE/LIKE '%keyword%' 模糊检索）
DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION
  WHEN insufficient_privilege THEN
    RAISE NOTICE 'skip CREATE EXTENSION pg_trgm: insufficient privilege';
END
$$;

-- 2) meta_category_def：逐级查询 + 状态过滤 + 排序
CREATE INDEX IF NOT EXISTS idx_meta_cat_def_parent_status_sort_code
  ON plm_meta.meta_category_def(parent_def_id, lower(status), sort_order, code_key);

CREATE INDEX IF NOT EXISTS idx_meta_cat_def_depth_status_sort_code
  ON plm_meta.meta_category_def(depth, lower(status), sort_order, code_key);

CREATE INDEX IF NOT EXISTS idx_meta_cat_def_status_code
  ON plm_meta.meta_category_def(lower(status), code_key);

-- 3) category_hierarchy：闭包表复合索引（祖先查子树 / 后代查路径）
CREATE INDEX IF NOT EXISTS idx_cat_hierarchy_ancestor_distance_desc
  ON plm_meta.category_hierarchy(ancestor_def_id, distance, descendant_def_id);

CREATE INDEX IF NOT EXISTS idx_cat_hierarchy_desc_distance_ancestor
  ON plm_meta.category_hierarchy(descendant_def_id, distance, ancestor_def_id);

-- 4) meta_category_version：latest 标题 join 与关键词搜索
CREATE INDEX IF NOT EXISTS idx_category_version_latest_def
  ON plm_meta.meta_category_version(is_latest, category_def_id);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_cat_ver_latest_display_name_trgm ON plm_meta.meta_category_version USING gin (lower(display_name) gin_trgm_ops) WHERE is_latest = true';
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_cat_def_code_key_trgm ON plm_meta.meta_category_def USING gin (lower(code_key) gin_trgm_ops)';
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_cat_def_full_path_name_trgm ON plm_meta.meta_category_def USING gin (lower(full_path_name) gin_trgm_ops)';
  ELSE
    RAISE NOTICE 'pg_trgm not installed, skip trgm indexes for category keyword search';
  END IF;
END
$$;

-- ===================== END V16 =======================================
