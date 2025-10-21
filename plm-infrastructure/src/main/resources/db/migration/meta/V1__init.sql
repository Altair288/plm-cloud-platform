-- =============================================================
-- 初始元数据建库：编码规则与分类/属性/枚举定义 + 版本化表
-- 目标数据库：plm_meta  (如使用多个 schema，可在前面加 schema 名)
-- 注意：版本表设计为不可变记录，更新通过插入新版本；不要对旧版本做 UPDATE。
-- =============================================================

-- =============== 基础扩展: 若需要单独 schema 可启用 =================
CREATE SCHEMA IF NOT EXISTS plm_meta;
SET search_path TO plm_meta;

-- =============== 编码规则模板表 ================================
CREATE TABLE IF NOT EXISTS plm_meta.meta_code_rule (
	id               UUID PRIMARY KEY,
	target_type      VARCHAR(32) NOT NULL,         -- category / attribute / lov
	expression       TEXT NOT NULL,                -- 模板表达式示例: <categoryCode>ATT_<SEQ4>  (避免使用防止 Flyway 解析占位符)
	inherit_from     VARCHAR(32),                  -- 继承来源层级描述 (optional)
	active           BOOLEAN NOT NULL DEFAULT TRUE,
	remark           TEXT,
	created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
	created_by       VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_code_rule_target ON meta_code_rule(target_type) WHERE active;

-- =============== 分类主定义 & 版本 =============================
CREATE TABLE IF NOT EXISTS plm_meta.meta_category_def (
	id           UUID PRIMARY KEY,
	code_key     VARCHAR(64) NOT NULL,      -- 逻辑标识（非最终编码）
	status       VARCHAR(20) NOT NULL DEFAULT 'active',
	created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
	created_by   VARCHAR(64),
	UNIQUE(code_key)
);

CREATE TABLE IF NOT EXISTS plm_meta.meta_category_version (
	id                       UUID PRIMARY KEY,
	category_def_id          UUID NOT NULL REFERENCES meta_category_def(id) ON DELETE CASCADE,
	version_no               INT  NOT NULL,               -- 从 1 递增
	display_name             VARCHAR(255),
	rule_resolved_code_prefix VARCHAR(128),              -- 如: CAT_BTN_
	structure_json           JSONB NOT NULL,             -- 分类扩展定义
	hash                     VARCHAR(64),
	is_latest                BOOLEAN NOT NULL DEFAULT TRUE,
	created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
	created_by               VARCHAR(64),
	UNIQUE(category_def_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_category_version_latest ON meta_category_version(category_def_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_category_version_hash ON meta_category_version(hash);
CREATE INDEX IF NOT EXISTS idx_category_version_structure_gin ON meta_category_version USING gin (structure_json jsonb_path_ops);

-- =============== 属性主定义 & 版本 =============================
CREATE TABLE IF NOT EXISTS plm_meta.meta_attribute_def (
	id             UUID PRIMARY KEY,
	category_def_id UUID NOT NULL REFERENCES meta_category_def(id) ON DELETE CASCADE,
	key            VARCHAR(128) NOT NULL,              -- 逻辑名称，如 button_type
	status         VARCHAR(20) NOT NULL DEFAULT 'active',
	created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
	created_by     VARCHAR(64),
	UNIQUE(category_def_id, key)
);

CREATE TABLE IF NOT EXISTS plm_meta.meta_attribute_version (
	id                     UUID PRIMARY KEY,
	attribute_def_id       UUID NOT NULL REFERENCES meta_attribute_def(id) ON DELETE CASCADE,
	category_version_id    UUID NOT NULL REFERENCES meta_category_version(id) ON DELETE RESTRICT, -- 绑定所基于的分类版本
	version_no             INT NOT NULL,
	resolved_code_prefix   VARCHAR(128),                -- 如: CAT_BTN_ATT_
	structure_json         JSONB NOT NULL,              -- 属性详细定义
	hash                   VARCHAR(64),
	is_latest              BOOLEAN NOT NULL DEFAULT TRUE,
	created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
	created_by             VARCHAR(64),
	UNIQUE(attribute_def_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_attribute_version_latest ON meta_attribute_version(attribute_def_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_attribute_version_hash ON meta_attribute_version(hash);
CREATE INDEX IF NOT EXISTS idx_attribute_version_structure_gin ON meta_attribute_version USING gin (structure_json jsonb_path_ops);

-- =============== LOV(枚举值) 主定义 & 版本 =====================
CREATE TABLE IF NOT EXISTS plm_meta.meta_lov_def (
	id               UUID PRIMARY KEY,
	attribute_def_id UUID NOT NULL REFERENCES meta_attribute_def(id) ON DELETE CASCADE,
	key              VARCHAR(128) NOT NULL,            -- 逻辑 key，如 normally_open
	status           VARCHAR(20) NOT NULL DEFAULT 'active',
	created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
	created_by       VARCHAR(64),
	UNIQUE(attribute_def_id, key)
);

CREATE TABLE IF NOT EXISTS plm_meta.meta_lov_version (
	id                   UUID PRIMARY KEY,
	lov_def_id           UUID NOT NULL REFERENCES meta_lov_def(id) ON DELETE CASCADE,
	attribute_version_id UUID NOT NULL REFERENCES meta_attribute_version(id) ON DELETE RESTRICT,
	version_no           INT NOT NULL,
	resolved_code_prefix VARCHAR(192),              -- 如: CAT_BTN_ATT_0003_VAL_
	value_json           JSONB NOT NULL,            -- 枚举内容 { value: '常开', i18n: {...}, order: 1 }
	hash                 VARCHAR(64),
	is_latest            BOOLEAN NOT NULL DEFAULT TRUE,
	created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
	created_by           VARCHAR(64),
	UNIQUE(lov_def_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_lov_version_latest ON meta_lov_version(lov_def_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_lov_version_hash ON meta_lov_version(hash);
CREATE INDEX IF NOT EXISTS idx_lov_version_value_gin ON meta_lov_version USING gin (value_json jsonb_path_ops);

-- =============== 编码序列表（统一管理各定义递增序号） ============
CREATE TABLE IF NOT EXISTS plm_meta.meta_code_sequence (
	id          BIGSERIAL PRIMARY KEY,
	scope_type  VARCHAR(32) NOT NULL,          -- category / attribute / lov
	scope_id    UUID NOT NULL,                 -- 指向对应 def 主键
	last_number INT  NOT NULL DEFAULT 0,
	updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
	UNIQUE(scope_type, scope_id)
);
CREATE INDEX IF NOT EXISTS idx_code_seq_scope ON meta_code_sequence(scope_type, scope_id);

-- =============== 维护触发器/约束建议（此处仅注释） ===============
-- 将来可添加触发器：
-- 1) 插入新 *version 时自动把旧版本 is_latest=false
-- 2) 根据结构 JSON 计算 hash
-- 示例函数（略）: CREATE FUNCTION set_old_versions_not_latest() ...

-- =============== 安全回滚说明 ================================
-- 回滚策略：Flyway 不建议回滚，变更需新版本迁移；如需开发环境回滚可手工 DROP TABLE。
-- =============================================================
