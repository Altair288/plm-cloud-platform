-- V3__add_category_hierarchy.sql
-- 扩展分类定义层级支持 (Materialized Path + Closure Table)
-- 模式：在 meta_category_def 增加层级字段；新增闭包表 plm_meta.category_hierarchy
-- 仅结构迁移，不做数据填充；后续导入脚本可批量插入并构建闭包。

SET search_path TO plm_meta, public;

-- 1. 扩展 meta_category_def 层级相关列 (幂等)
ALTER TABLE plm_meta.meta_category_def
  ADD COLUMN IF NOT EXISTS parent_def_id UUID NULL REFERENCES plm_meta.meta_category_def(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS path TEXT NULL,
  ADD COLUMN IF NOT EXISTS depth SMALLINT NULL,
  ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS full_name TEXT NULL,
  ADD COLUMN IF NOT EXISTS is_leaf BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. 为高频查询建立索引
CREATE INDEX IF NOT EXISTS idx_meta_cat_def_parent ON plm_meta.meta_category_def(parent_def_id);
CREATE INDEX IF NOT EXISTS idx_meta_cat_def_path   ON plm_meta.meta_category_def(path);
CREATE INDEX IF NOT EXISTS idx_meta_cat_def_depth  ON plm_meta.meta_category_def(depth);
CREATE INDEX IF NOT EXISTS idx_meta_cat_def_leaf   ON plm_meta.meta_category_def(is_leaf);

-- 3. 闭包表：存储祖先/后代关系
CREATE TABLE IF NOT EXISTS plm_meta.category_hierarchy (
  ancestor_def_id   UUID NOT NULL REFERENCES plm_meta.meta_category_def(id) ON DELETE CASCADE,
  descendant_def_id UUID NOT NULL REFERENCES plm_meta.meta_category_def(id) ON DELETE CASCADE,
  distance SMALLINT NOT NULL, -- 祖先到后代的层级距离，自己到自己为0
  PRIMARY KEY (ancestor_def_id, descendant_def_id)
);
CREATE INDEX IF NOT EXISTS idx_cat_hierarchy_ancestor ON plm_meta.category_hierarchy(ancestor_def_id);
CREATE INDEX IF NOT EXISTS idx_cat_hierarchy_desc     ON plm_meta.category_hierarchy(descendant_def_id);

-- 4. 约束与初始状态说明
-- path / depth / full_name / is_leaf 由后续数据导入或维护脚本计算，不在迁移中强制。
-- 可在后续添加触发器保持 depth= array_length(string_to_array(path,'/'),1) 一致。

-- 5. 校验辅助函数 (可选，暂留占位，未来需要可添加):
-- CREATE FUNCTION plm_meta.recalc_is_leaf() RETURNS void AS $$ ... $$ LANGUAGE plpgsql;

-- ============ 结束 V3 ============