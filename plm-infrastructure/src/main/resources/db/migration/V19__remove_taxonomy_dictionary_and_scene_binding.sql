-- =====================================================================
-- V19: 移除 taxonomy 字典及场景绑定
-- =====================================================================

SET search_path TO plm_meta, public;

-- 更新分类管理场景，不再包含 META_TAXONOMY
UPDATE plm_meta.meta_dictionary_scene
SET dictionary_codes = '["META_CATEGORY_BUSINESS_DOMAIN", "META_CATEGORY_STATUS"]'::jsonb
WHERE scene_code = 'category-admin'
  AND lower(coalesce(status, '')) <> 'deleted';

-- 删除 taxonomy 字典项（若存在）
DELETE FROM plm_meta.meta_dictionary_item i
USING plm_meta.meta_dictionary_def d
WHERE i.dict_def_id = d.id
  AND d.dict_code = 'META_TAXONOMY';

-- 删除 taxonomy 字典定义（若存在）
DELETE FROM plm_meta.meta_dictionary_def
WHERE dict_code = 'META_TAXONOMY';

-- ===================== END V19 =======================================
