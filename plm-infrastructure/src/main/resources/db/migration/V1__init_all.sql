-- =====================================================================
-- Unified V1 baseline migration
-- 合并原先多版本(meta/runtime)所有表结构 & 约束 & 调整后的唯一性规则。
-- 覆盖范围：
--   * Schemas: plm_meta, plm
--   * 元数据 & 版本化：分类/属性/LOV 定义与版本表
--   * 编码规则 + 规则版本 + 序列表 (兼容当前代码生成器需要的 pattern 字段)
--   * 运行时：账号 / 角色 / 权限 / 分类实例 / 属性值 / 客户配置快照
--   * 简化业务分类/属性 (非版本化) + 复合唯一约束 (category_id, code)
-- 说明：这是新的基线，初始化环境请务必清空旧库或 DROP flyway_schema_history
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS plm_meta;
CREATE SCHEMA IF NOT EXISTS plm;

-- 可选：gen_random_uuid() 需要 pgcrypto
DO $$ BEGIN
  PERFORM 1 FROM pg_extension WHERE extname='pgcrypto';
  IF NOT FOUND THEN
    CREATE EXTENSION pgcrypto;
  END IF;
END $$;

-- ===================== META: 编码规则主表 =============================
-- 兼容旧设计( target_type / inherit_from / active / remark ) 与当前代码生成需要的 code + pattern
CREATE TABLE IF NOT EXISTS plm_meta.meta_code_rule (
    id            UUID PRIMARY KEY,
    code          VARCHAR(64) NOT NULL UNIQUE,  -- 规则编码 (供应用使用)
    name          VARCHAR(128) NOT NULL,
    target_type   VARCHAR(32) NOT NULL,         -- category / attribute / lov 等
    pattern       VARCHAR(128) NOT NULL,        -- 直接用于生成的模板 (支持 {DATE} {SEQ})
    inherit_from  VARCHAR(32),                  -- 继承层级（预留）
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    remark        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_meta_code_rule_target ON plm_meta.meta_code_rule (target_type) WHERE active;

-- ===================== META: 编码规则版本表 ============================
CREATE TABLE IF NOT EXISTS plm_meta.meta_code_rule_version (
    id            UUID PRIMARY KEY,
    code_rule_id  UUID NOT NULL REFERENCES plm_meta.meta_code_rule(id) ON DELETE CASCADE,
    version_no    INT  NOT NULL,
    rule_json     JSONB NOT NULL,                -- { pattern:"...", length:..., step:..., inheritFrom:... }
    hash          VARCHAR(64),
    is_latest     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(64),
    UNIQUE(code_rule_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_code_rule_version_latest ON plm_meta.meta_code_rule_version(code_rule_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_code_rule_version_hash   ON plm_meta.meta_code_rule_version(hash);

-- ===================== META: 分类定义 & 版本 ===========================
CREATE TABLE IF NOT EXISTS plm_meta.meta_category_def (
    id          UUID PRIMARY KEY,
    code_key    VARCHAR(64) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(64),
    UNIQUE(code_key)
);

CREATE TABLE IF NOT EXISTS plm_meta.meta_category_version (
    id                        UUID PRIMARY KEY,
    category_def_id           UUID NOT NULL REFERENCES plm_meta.meta_category_def(id) ON DELETE CASCADE,
    version_no                INT NOT NULL,
    display_name              VARCHAR(255),
    rule_resolved_code_prefix VARCHAR(128),
    structure_json            JSONB NOT NULL,
    hash                      VARCHAR(64),
    is_latest                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                VARCHAR(64),
    UNIQUE(category_def_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_category_version_latest ON plm_meta.meta_category_version(category_def_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_category_version_hash   ON plm_meta.meta_category_version(hash);
CREATE INDEX IF NOT EXISTS idx_category_version_structure_gin ON plm_meta.meta_category_version USING gin (structure_json jsonb_path_ops);

-- ===================== META: 属性定义 & 版本 ===========================
CREATE TABLE IF NOT EXISTS plm_meta.meta_attribute_def (
    id              UUID PRIMARY KEY,
    category_def_id UUID NOT NULL REFERENCES plm_meta.meta_category_def(id) ON DELETE CASCADE,
    key             VARCHAR(128) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(64),
    UNIQUE(category_def_id, key)
);

CREATE TABLE IF NOT EXISTS plm_meta.meta_attribute_version (
    id                   UUID PRIMARY KEY,
    attribute_def_id     UUID NOT NULL REFERENCES plm_meta.meta_attribute_def(id) ON DELETE CASCADE,
    category_version_id  UUID NOT NULL REFERENCES plm_meta.meta_category_version(id) ON DELETE RESTRICT,
    version_no           INT NOT NULL,
    resolved_code_prefix VARCHAR(128),
    structure_json       JSONB NOT NULL,
    hash                 VARCHAR(64),
    is_latest            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           VARCHAR(64),
    UNIQUE(attribute_def_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_attribute_version_latest ON plm_meta.meta_attribute_version(attribute_def_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_attribute_version_hash   ON plm_meta.meta_attribute_version(hash);
CREATE INDEX IF NOT EXISTS idx_attribute_version_structure_gin ON plm_meta.meta_attribute_version USING gin (structure_json jsonb_path_ops);

-- ===================== META: LOV 定义 & 版本 ===========================
CREATE TABLE IF NOT EXISTS plm_meta.meta_lov_def (
    id              UUID PRIMARY KEY,
    attribute_def_id UUID NOT NULL REFERENCES plm_meta.meta_attribute_def(id) ON DELETE CASCADE,
    key             VARCHAR(128) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(64),
    UNIQUE(attribute_def_id, key)
);

CREATE TABLE IF NOT EXISTS plm_meta.meta_lov_version (
    id                   UUID PRIMARY KEY,
    lov_def_id           UUID NOT NULL REFERENCES plm_meta.meta_lov_def(id) ON DELETE CASCADE,
    attribute_version_id UUID NOT NULL REFERENCES plm_meta.meta_attribute_version(id) ON DELETE RESTRICT,
    version_no           INT NOT NULL,
    resolved_code_prefix VARCHAR(192),
    value_json           JSONB NOT NULL,
    hash                 VARCHAR(64),
    is_latest            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           VARCHAR(64),
    UNIQUE(lov_def_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_lov_version_latest ON plm_meta.meta_lov_version(lov_def_id, is_latest);
CREATE INDEX IF NOT EXISTS idx_lov_version_hash   ON plm_meta.meta_lov_version(hash);
CREATE INDEX IF NOT EXISTS idx_lov_version_value_gin ON plm_meta.meta_lov_version USING gin (value_json jsonb_path_ops);

-- ===================== META: 序列表（供 CodeRuleGenerator 使用） ========
-- 简化为 rule_code -> current_value，以适配当前实现；如需 scope 粒度可另建扩展表
CREATE TABLE IF NOT EXISTS plm_meta.meta_code_sequence (
    rule_code     VARCHAR(64) PRIMARY KEY REFERENCES plm_meta.meta_code_rule(code),
    current_value BIGINT NOT NULL DEFAULT 0
);

-- ===================== RUNTIME: 用户 / 角色 / 权限 =====================
CREATE TABLE IF NOT EXISTS plm.user_account (
    id            UUID PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(128),
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    email         VARCHAR(128),
    phone         VARCHAR(32),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS plm.role (
    id         UUID PRIMARY KEY,
    code       VARCHAR(64) NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS plm.permission (
    id         UUID PRIMARY KEY,
    code       VARCHAR(96) NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS plm.role_permission (
    role_id       UUID NOT NULL REFERENCES plm.role(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES plm.permission(id) ON DELETE CASCADE,
    PRIMARY KEY(role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS plm.user_role (
    user_id UUID NOT NULL REFERENCES plm.user_account(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES plm.role(id) ON DELETE CASCADE,
    PRIMARY KEY(user_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_user_role_user ON plm.user_role(user_id);

-- ===================== RUNTIME: 分类实例 & 属性值 =======================
CREATE TABLE IF NOT EXISTS plm.category_instance (
    id                   UUID PRIMARY KEY,
    category_version_id  UUID NOT NULL, -- 引用 plm_meta.meta_category_version(id) (应用层校验)
    code                 VARCHAR(128) NOT NULL UNIQUE,
    name                 VARCHAR(255),
    status               VARCHAR(20) NOT NULL DEFAULT 'active',
    snapshot_json        JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_category_instance_version ON plm.category_instance(category_version_id);
CREATE INDEX IF NOT EXISTS idx_category_instance_snapshot_gin ON plm.category_instance USING gin (snapshot_json jsonb_path_ops);

CREATE TABLE IF NOT EXISTS plm.attribute_value (
    id                   UUID PRIMARY KEY,
    category_instance_id UUID NOT NULL REFERENCES plm.category_instance(id) ON DELETE CASCADE,
    attribute_version_id UUID NOT NULL, -- 引用 plm_meta.meta_attribute_version(id) (应用层校验)
    value_text           TEXT,
    value_number         NUMERIC(30,8),
    value_bool           BOOLEAN,
    value_json           JSONB,
    status               VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           VARCHAR(64),
    UNIQUE(category_instance_id, attribute_version_id)
);
CREATE INDEX IF NOT EXISTS idx_attr_value_instance     ON plm.attribute_value(category_instance_id);
CREATE INDEX IF NOT EXISTS idx_attr_value_attr_version ON plm.attribute_value(attribute_version_id);
CREATE INDEX IF NOT EXISTS idx_attr_value_json_gin     ON plm.attribute_value USING gin (value_json jsonb_path_ops);

-- ===================== RUNTIME: 客户配置快照 ===========================
CREATE TABLE IF NOT EXISTS plm.customer_configuration (
    id                  UUID PRIMARY KEY,
    customer_id         VARCHAR(64) NOT NULL,
    name                VARCHAR(200),
    category_code       VARCHAR(64) NOT NULL,
    category_version_id UUID NOT NULL,
    snapshot_json       JSONB NOT NULL,
    selection_json      JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_customer_conf_customer ON plm.customer_configuration(customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_conf_category ON plm.customer_configuration(category_code);
CREATE INDEX IF NOT EXISTS idx_customer_conf_snapshot_gin ON plm.customer_configuration USING gin (snapshot_json jsonb_path_ops);

-- ===================== 业务层（非版本化）分类 & 属性 ====================
CREATE TABLE IF NOT EXISTS plm.category (
    id          UUID PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_category_name ON plm.category(name);

CREATE TABLE IF NOT EXISTS plm.attribute (
    id          UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES plm.category(id) ON DELETE CASCADE,
    code        VARCHAR(96) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32) NOT NULL,
    unit        VARCHAR(32),
    lov_code    VARCHAR(64),
    sort_order  INT NOT NULL DEFAULT 0,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(64),
    UNIQUE(category_id, code),              -- 直接使用最终复合唯一 (已整合 V5 调整)
    UNIQUE(category_id, name)
);
CREATE INDEX IF NOT EXISTS idx_attribute_category ON plm.attribute(category_id);
CREATE INDEX IF NOT EXISTS idx_attribute_type     ON plm.attribute(type);

-- ===================== Seeds (基础编码规则) =============================
INSERT INTO plm_meta.meta_code_rule (id, code, name, target_type, pattern)
    VALUES (gen_random_uuid(), 'CATEGORY',  '分类编码规则',  'category',  'CAT-{DATE}-{SEQ}')
    ON CONFLICT (code) DO NOTHING;
INSERT INTO plm_meta.meta_code_rule (id, code, name, target_type, pattern)
    VALUES (gen_random_uuid(), 'ATTRIBUTE', '属性编码规则', 'attribute', 'ATT-{DATE}-{SEQ}')
    ON CONFLICT (code) DO NOTHING;

INSERT INTO plm_meta.meta_code_sequence (rule_code, current_value) VALUES ('CATEGORY', 0)  ON CONFLICT (rule_code) DO NOTHING;
INSERT INTO plm_meta.meta_code_sequence (rule_code, current_value) VALUES ('ATTRIBUTE', 0) ON CONFLICT (rule_code) DO NOTHING;

-- 初始化编码规则版本（仅当版本表中不存在该规则版本时）
INSERT INTO plm_meta.meta_code_rule_version (id, code_rule_id, version_no, rule_json, is_latest, created_at)
SELECT gen_random_uuid(), r.id, 1,
       jsonb_build_object('pattern', r.pattern, 'step', 1, 'inheritFrom', r.inherit_from),
       TRUE, now()
FROM plm_meta.meta_code_rule r
LEFT JOIN plm_meta.meta_code_rule_version v ON v.code_rule_id = r.id
WHERE v.code_rule_id IS NULL;

-- ===================== 结束 =============================
