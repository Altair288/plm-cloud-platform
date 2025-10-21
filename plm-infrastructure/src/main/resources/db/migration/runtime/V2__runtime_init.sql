-- =============================================================
-- V2__runtime_init.sql
-- 运行时数据（逻辑库 plm）基础表：用户 / 角色 / 权限 / 分类实例 / 属性值
-- 若实际部署是独立数据库 plm，请在第二次 Flyway 运行时连接那个数据库。
-- 当前脚本作为示例使用独立 schema plm 来体现物理隔离意图。
-- =============================================================

CREATE SCHEMA IF NOT EXISTS plm;
SET search_path TO plm;

-- =============== 用户与权限体系 =============================
CREATE TABLE IF NOT EXISTS user_account (
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

CREATE TABLE IF NOT EXISTS role (
  id         UUID PRIMARY KEY,
  code       VARCHAR(64) NOT NULL UNIQUE,
  name       VARCHAR(128) NOT NULL,
  status     VARCHAR(20) NOT NULL DEFAULT 'active',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS permission (
  id         UUID PRIMARY KEY,
  code       VARCHAR(96) NOT NULL UNIQUE, -- 如: CAT:READ / ATTR:WRITE
  name       VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS role_permission (
  role_id       UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
  permission_id UUID NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
  PRIMARY KEY(role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS user_role (
  user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  role_id UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
  PRIMARY KEY(user_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_user_role_user ON user_role(user_id);

-- =============== 分类实例 & 属性值 ===========================
-- category_instance 代表具体业务对象所引用的分类版本快照
CREATE TABLE IF NOT EXISTS category_instance (
  id                    UUID PRIMARY KEY,
  category_version_id   UUID NOT NULL, -- 引用 plm_meta.meta_category_version(id)，跨库/跨schema引用需在应用层校验
  code                  VARCHAR(128) NOT NULL UNIQUE, -- 实际生成编码
  name                  VARCHAR(255),
  status                VARCHAR(20) NOT NULL DEFAULT 'active',
  snapshot_json         JSONB,            -- 分类版本快照（冗余）
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by            VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_category_instance_version ON category_instance(category_version_id);
CREATE INDEX IF NOT EXISTS idx_category_instance_snapshot_gin ON category_instance USING gin(snapshot_json jsonb_path_ops);

-- attribute_value 记录某实例具体的属性值
CREATE TABLE IF NOT EXISTS attribute_value (
  id                    UUID PRIMARY KEY,
  category_instance_id  UUID NOT NULL REFERENCES category_instance(id) ON DELETE CASCADE,
  attribute_version_id  UUID NOT NULL,  -- 引用 plm_meta.meta_attribute_version(id)
  value_text            TEXT,           -- 文本型
  value_number          NUMERIC(30,8),  -- 数值型
  value_bool            BOOLEAN,
  value_json            JSONB,          -- 复杂/枚举/结构化值
  status                VARCHAR(20) NOT NULL DEFAULT 'active',
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by            VARCHAR(64),
  UNIQUE(category_instance_id, attribute_version_id)
);
CREATE INDEX IF NOT EXISTS idx_attr_value_instance ON attribute_value(category_instance_id);
CREATE INDEX IF NOT EXISTS idx_attr_value_attr_version ON attribute_value(attribute_version_id);
CREATE INDEX IF NOT EXISTS idx_attr_value_json_gin ON attribute_value USING gin(value_json jsonb_path_ops);

-- 简单枚举值引用（若需要快速查找 LOV 值，可单独拆表；此处留可扩展位）
-- 可在后续版本增加 attribute_value_lov linking 到 meta_lov_version

-- =============== 说明 =============================
-- 1) 与 plm_meta 的引用未加外键（跨 schema/跨库时可不方便），由应用层保证引用有效性。
-- 2) 后续若两库物理分离，需将 meta 引用字段保留为 UUID，并在服务层通过 API 查询校验。
-- 3) 编码生成使用 plm_meta.meta_code_sequence，实例插入前获取下一个序号生成 code。
-- =============================================================
