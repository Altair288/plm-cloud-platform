-- =====================================================================
-- V18: 通用字典接口基础表 + 分类字典初始化数据
-- =====================================================================

SET search_path TO plm_meta, public;

CREATE TABLE IF NOT EXISTS plm_meta.meta_dictionary_def (
    id           UUID PRIMARY KEY,
    dict_code    VARCHAR(64) NOT NULL,
    dict_name    VARCHAR(128) NOT NULL,
    source_type  VARCHAR(32) NOT NULL DEFAULT 'DB',
    locale       VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    status       VARCHAR(20) NOT NULL DEFAULT 'active',
    version_no   INT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   VARCHAR(64),
    CONSTRAINT uq_meta_dictionary_def_code_locale UNIQUE(dict_code, locale)
);

CREATE INDEX IF NOT EXISTS idx_meta_dict_def_status_code
    ON plm_meta.meta_dictionary_def(lower(status), dict_code);

CREATE TABLE IF NOT EXISTS plm_meta.meta_dictionary_item (
    id           UUID PRIMARY KEY,
    dict_def_id  UUID NOT NULL REFERENCES plm_meta.meta_dictionary_def(id) ON DELETE CASCADE,
    item_key     VARCHAR(64) NOT NULL,
    item_value   VARCHAR(64) NOT NULL,
    label        VARCHAR(255) NOT NULL,
    sort_order   INT NOT NULL DEFAULT 0,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    extra_json   JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   VARCHAR(64),
    CONSTRAINT uq_meta_dictionary_item_def_key UNIQUE(dict_def_id, item_key)
);

CREATE INDEX IF NOT EXISTS idx_meta_dict_item_def_sort
    ON plm_meta.meta_dictionary_item(dict_def_id, sort_order, item_key);

CREATE TABLE IF NOT EXISTS plm_meta.meta_dictionary_scene (
    id                UUID PRIMARY KEY,
    scene_code        VARCHAR(64) NOT NULL,
    scene_name        VARCHAR(128) NOT NULL,
    locale            VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    status            VARCHAR(20) NOT NULL DEFAULT 'active',
    dictionary_codes  JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        VARCHAR(64),
    CONSTRAINT uq_meta_dictionary_scene_code_locale UNIQUE(scene_code, locale)
);

CREATE INDEX IF NOT EXISTS idx_meta_dict_scene_status_code
    ON plm_meta.meta_dictionary_scene(lower(status), scene_code);

-- ===================== seed: META_CATEGORY_BUSINESS_DOMAIN =====================
INSERT INTO plm_meta.meta_dictionary_def(id, dict_code, dict_name, source_type, locale, status, version_no, created_by)
VALUES (gen_random_uuid(), 'META_CATEGORY_BUSINESS_DOMAIN', '分类业务领域', 'DB', 'zh-CN', 'active', 1, 'system')
ON CONFLICT (dict_code, locale) DO NOTHING;

INSERT INTO plm_meta.meta_dictionary_item(id, dict_def_id, item_key, item_value, label, sort_order, enabled, extra_json, created_by)
SELECT gen_random_uuid(), d.id, v.item_key, v.item_value, v.label, v.sort_order, true, NULL, 'system'
FROM plm_meta.meta_dictionary_def d
JOIN (
    VALUES
        ('PRODUCT', 'PRODUCT', '产品', 1),
        ('MATERIAL', 'MATERIAL', '物料', 2),
        ('BOM', 'BOM', 'BOM', 3),
        ('PROCESS', 'PROCESS', '工艺', 4),
        ('TEST', 'TEST', '测试', 5),
        ('EXPERIMENT', 'EXPERIMENT', '实验', 6)
) v(item_key, item_value, label, sort_order) ON 1 = 1
WHERE d.dict_code = 'META_CATEGORY_BUSINESS_DOMAIN'
  AND d.locale = 'zh-CN'
ON CONFLICT (dict_def_id, item_key) DO NOTHING;

-- ===================== seed: META_CATEGORY_STATUS =====================
INSERT INTO plm_meta.meta_dictionary_def(id, dict_code, dict_name, source_type, locale, status, version_no, created_by)
VALUES (gen_random_uuid(), 'META_CATEGORY_STATUS', '分类状态', 'DB', 'zh-CN', 'active', 1, 'system')
ON CONFLICT (dict_code, locale) DO NOTHING;

INSERT INTO plm_meta.meta_dictionary_item(id, dict_def_id, item_key, item_value, label, sort_order, enabled, extra_json, created_by)
SELECT gen_random_uuid(), d.id, v.item_key, v.item_value, v.label, v.sort_order, v.enabled,
       jsonb_build_object('dbValue', v.db_value),
       'system'
FROM plm_meta.meta_dictionary_def d
JOIN (
    VALUES
        ('CREATED', 'CREATED', '创建', 1, true,  'draft'),
        ('EFFECTIVE', 'EFFECTIVE', '生效', 2, true,  'active'),
        ('INVALID', 'INVALID', '失效', 3, true,  'inactive'),
        ('DELETED', 'DELETED', '删除', 4, false, 'deleted')
) v(item_key, item_value, label, sort_order, enabled, db_value) ON 1 = 1
WHERE d.dict_code = 'META_CATEGORY_STATUS'
  AND d.locale = 'zh-CN'
ON CONFLICT (dict_def_id, item_key) DO NOTHING;

-- ===================== seed: META_TAXONOMY (service-backed placeholder) =====================
INSERT INTO plm_meta.meta_dictionary_def(id, dict_code, dict_name, source_type, locale, status, version_no, created_by)
VALUES (gen_random_uuid(), 'META_TAXONOMY', '分类体系', 'SERVICE', 'zh-CN', 'active', 1, 'system')
ON CONFLICT (dict_code, locale) DO NOTHING;

-- ===================== seed: scene category-admin =====================
INSERT INTO plm_meta.meta_dictionary_scene(id, scene_code, scene_name, locale, status, dictionary_codes, created_by)
VALUES (
    gen_random_uuid(),
    'category-admin',
    '分类管理场景',
    'zh-CN',
    'active',
    '["META_CATEGORY_BUSINESS_DOMAIN", "META_CATEGORY_STATUS", "META_TAXONOMY"]'::jsonb,
    'system'
)
ON CONFLICT (scene_code, locale) DO NOTHING;

-- ===================== END V18 =======================================
