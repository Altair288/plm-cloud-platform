CREATE SCHEMA IF NOT EXISTS plm_platform;
CREATE SCHEMA IF NOT EXISTS plm_runtime;

CREATE TABLE IF NOT EXISTS plm_platform.user_account (
    id             UUID PRIMARY KEY,
    username       VARCHAR(64) NOT NULL,
    display_name   VARCHAR(128) NOT NULL,
    email          VARCHAR(128),
    phone          VARCHAR(32),
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_type    VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     VARCHAR(64),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_user_account_username_ci
    ON plm_platform.user_account (lower(username));
CREATE UNIQUE INDEX IF NOT EXISTS uidx_user_account_email_ci
    ON plm_platform.user_account (lower(email))
    WHERE email IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uidx_user_account_phone
    ON plm_platform.user_account (phone)
    WHERE phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_user_account_status
    ON plm_platform.user_account (status);
CREATE INDEX IF NOT EXISTS idx_user_account_registered_at
    ON plm_platform.user_account (registered_at);

CREATE TABLE IF NOT EXISTS plm_platform.user_credential (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES plm_platform.user_account(id) ON DELETE CASCADE,
    credential_type   VARCHAR(20) NOT NULL,
    secret_hash       VARCHAR(255) NOT NULL,
    secret_salt       VARCHAR(255),
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at        TIMESTAMPTZ,
    last_rotated_at   TIMESTAMPTZ,
    last_verified_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        VARCHAR(64),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(64),
    CONSTRAINT uk_user_credential_user_type UNIQUE (user_id, credential_type)
);

CREATE INDEX IF NOT EXISTS idx_user_credential_user_status
    ON plm_platform.user_credential (user_id, status);
CREATE INDEX IF NOT EXISTS idx_user_credential_expires_at
    ON plm_platform.user_credential (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS plm_platform.workspace (
    id                UUID PRIMARY KEY,
    workspace_code    VARCHAR(64) NOT NULL,
    workspace_name    VARCHAR(128) NOT NULL,
    workspace_status  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    owner_user_id     UUID NOT NULL REFERENCES plm_platform.user_account(id),
    workspace_type    VARCHAR(20) NOT NULL,
    lifecycle_stage   VARCHAR(20) NOT NULL,
    default_locale    VARCHAR(16) NOT NULL,
    default_timezone  VARCHAR(64) NOT NULL,
    config_json       JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        VARCHAR(64),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(64),
    CONSTRAINT uk_workspace_code UNIQUE (workspace_code)
);

CREATE INDEX IF NOT EXISTS idx_workspace_owner
    ON plm_platform.workspace (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_workspace_status
    ON plm_platform.workspace (workspace_status);
CREATE INDEX IF NOT EXISTS idx_workspace_type
    ON plm_platform.workspace (workspace_type);
CREATE INDEX IF NOT EXISTS idx_workspace_created_at
    ON plm_platform.workspace (created_at);
CREATE INDEX IF NOT EXISTS idx_workspace_config_gin
    ON plm_platform.workspace USING gin (config_json jsonb_path_ops);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_member (
    id                    UUID PRIMARY KEY,
    workspace_id          UUID NOT NULL REFERENCES plm_platform.workspace(id) ON DELETE CASCADE,
    user_id               UUID NOT NULL REFERENCES plm_platform.user_account(id) ON DELETE CASCADE,
    member_status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    join_type             VARCHAR(20) NOT NULL,
    joined_at             TIMESTAMPTZ,
    invited_by_user_id    UUID REFERENCES plm_platform.user_account(id),
    is_default_workspace  BOOLEAN NOT NULL DEFAULT FALSE,
    remark                VARCHAR(255),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            VARCHAR(64),
    updated_at            TIMESTAMPTZ,
    updated_by            VARCHAR(64),
    CONSTRAINT uk_workspace_member_workspace_user UNIQUE (workspace_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_workspace_member_workspace_status
    ON plm_platform.workspace_member (workspace_id, member_status);
CREATE INDEX IF NOT EXISTS idx_workspace_member_user_status
    ON plm_platform.workspace_member (user_id, member_status);
CREATE UNIQUE INDEX IF NOT EXISTS uidx_workspace_member_default_workspace
    ON plm_platform.workspace_member (user_id)
    WHERE is_default_workspace = TRUE;

CREATE TABLE IF NOT EXISTS plm_platform.permission (
    id               UUID PRIMARY KEY,
    permission_code  VARCHAR(96) NOT NULL,
    permission_name  VARCHAR(128) NOT NULL,
    scope_type       VARCHAR(20) NOT NULL,
    module_code      VARCHAR(64) NOT NULL,
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       VARCHAR(64),
    CONSTRAINT uk_permission_code UNIQUE (permission_code)
);

CREATE INDEX IF NOT EXISTS idx_permission_scope_module
    ON plm_platform.permission (scope_type, module_code);

CREATE TABLE IF NOT EXISTS plm_platform.platform_role (
    id             UUID PRIMARY KEY,
    role_code      VARCHAR(64) NOT NULL,
    role_name      VARCHAR(128) NOT NULL,
    role_status    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    built_in_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     VARCHAR(64),
    CONSTRAINT uk_platform_role_code UNIQUE (role_code)
);

CREATE INDEX IF NOT EXISTS idx_platform_role_status
    ON plm_platform.platform_role (role_status);

CREATE TABLE IF NOT EXISTS plm_platform.platform_user_role (
    user_id               UUID NOT NULL REFERENCES plm_platform.user_account(id) ON DELETE CASCADE,
    role_id               UUID NOT NULL REFERENCES plm_platform.platform_role(id) ON DELETE CASCADE,
    assigned_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by_user_id   UUID REFERENCES plm_platform.user_account(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_user_role_role
    ON plm_platform.platform_user_role (role_id);

CREATE TABLE IF NOT EXISTS plm_platform.platform_role_permission (
    role_id        UUID NOT NULL REFERENCES plm_platform.platform_role(id) ON DELETE CASCADE,
    permission_id  UUID NOT NULL REFERENCES plm_platform.permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_role_permission_permission
    ON plm_platform.platform_role_permission (permission_id);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_role (
    id             UUID PRIMARY KEY,
    workspace_id   UUID NOT NULL REFERENCES plm_platform.workspace(id) ON DELETE CASCADE,
    role_code      VARCHAR(64) NOT NULL,
    role_name      VARCHAR(128) NOT NULL,
    role_type      VARCHAR(20) NOT NULL DEFAULT 'CUSTOM',
    role_status    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    built_in_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     VARCHAR(64),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(64),
    CONSTRAINT uk_workspace_role_workspace_code UNIQUE (workspace_id, role_code)
);

CREATE INDEX IF NOT EXISTS idx_workspace_role_workspace_status
    ON plm_platform.workspace_role (workspace_id, role_status);
CREATE INDEX IF NOT EXISTS idx_workspace_role_workspace_type
    ON plm_platform.workspace_role (workspace_id, role_type);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_member_role (
    workspace_member_id   UUID NOT NULL REFERENCES plm_platform.workspace_member(id) ON DELETE CASCADE,
    workspace_role_id     UUID NOT NULL REFERENCES plm_platform.workspace_role(id) ON DELETE CASCADE,
    assigned_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by_user_id   UUID REFERENCES plm_platform.user_account(id),
    PRIMARY KEY (workspace_member_id, workspace_role_id)
);

CREATE INDEX IF NOT EXISTS idx_workspace_member_role_role
    ON plm_platform.workspace_member_role (workspace_role_id);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_role_permission (
    workspace_role_id  UUID NOT NULL REFERENCES plm_platform.workspace_role(id) ON DELETE CASCADE,
    permission_id      UUID NOT NULL REFERENCES plm_platform.permission(id) ON DELETE CASCADE,
    PRIMARY KEY (workspace_role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS idx_workspace_role_permission_permission
    ON plm_platform.workspace_role_permission (permission_id);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_invitation (
    id                   UUID PRIMARY KEY,
    workspace_id         UUID NOT NULL REFERENCES plm_platform.workspace(id) ON DELETE CASCADE,
    invitee_email        VARCHAR(128) NOT NULL,
    invitee_display_name VARCHAR(128),
    invited_by_user_id   UUID NOT NULL REFERENCES plm_platform.user_account(id),
    invitation_status    VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invitation_token     VARCHAR(128) NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    accepted_by_user_id  UUID REFERENCES plm_platform.user_account(id),
    accepted_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           VARCHAR(64),
    CONSTRAINT uk_workspace_invitation_token UNIQUE (invitation_token)
);

CREATE INDEX IF NOT EXISTS idx_workspace_invitation_workspace_status
    ON plm_platform.workspace_invitation (workspace_id, invitation_status);
CREATE INDEX IF NOT EXISTS idx_workspace_invitation_email_status
    ON plm_platform.workspace_invitation (lower(invitee_email), invitation_status);
CREATE INDEX IF NOT EXISTS idx_workspace_invitation_expires_at
    ON plm_platform.workspace_invitation (expires_at);

CREATE TABLE IF NOT EXISTS plm_platform.login_audit (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES plm_platform.user_account(id),
    login_type      VARCHAR(20) NOT NULL,
    login_result    VARCHAR(20) NOT NULL,
    login_ip        VARCHAR(64),
    user_agent      VARCHAR(512),
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_login_audit_user_created_at
    ON plm_platform.login_audit (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_login_audit_type_result_created_at
    ON plm_platform.login_audit (login_type, login_result, created_at DESC);

INSERT INTO plm_platform.permission (id, permission_code, permission_name, scope_type, module_code, description, created_by)
VALUES
    (gen_random_uuid(), 'platform.workspace.read', '平台侧查看 workspace', 'GLOBAL', 'platform.workspace', '查看 workspace 基础信息', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform.workspace.create', '平台侧创建 workspace', 'GLOBAL', 'platform.workspace', '创建 workspace', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform.workspace.update', '平台侧更新 workspace', 'GLOBAL', 'platform.workspace', '更新 workspace 基础信息', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform.workspace.freeze', '平台侧冻结 workspace', 'GLOBAL', 'platform.workspace', '冻结 workspace', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform.user.read', '平台侧查看用户', 'GLOBAL', 'platform.user', '查看用户基础信息', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform.user.assign-role', '平台侧分配平台角色', 'GLOBAL', 'platform.user', '为用户分配平台角色', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform.audit.read', '平台侧查看审计', 'GLOBAL', 'platform.audit', '查看登录与操作审计', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.member.read', '空间侧查看成员', 'WORKSPACE', 'workspace.member', '查看 workspace 成员', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.member.invite', '空间侧邀请成员', 'WORKSPACE', 'workspace.member', '邀请成员加入 workspace', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.member.disable', '空间侧禁用成员', 'WORKSPACE', 'workspace.member', '禁用或移除成员', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.member.assign-role', '空间侧分配成员角色', 'WORKSPACE', 'workspace.member', '为成员分配 workspace 角色', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.profile.read', '空间侧查看空间信息', 'WORKSPACE', 'workspace.profile', '查看 workspace 基础配置', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.profile.update', '空间侧更新空间信息', 'WORKSPACE', 'workspace.profile', '更新 workspace 基础配置', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.config.read', '空间侧查看空间配置', 'WORKSPACE', 'workspace.config', '查看 workspace 配置项', 'FLYWAY_V33'),
    (gen_random_uuid(), 'workspace.config.update', '空间侧更新空间配置', 'WORKSPACE', 'workspace.config', '更新 workspace 配置项', 'FLYWAY_V33'),
    (gen_random_uuid(), 'runtime.import.execute', '运行态导入执行', 'WORKSPACE', 'runtime.import', '执行运行态导入', 'FLYWAY_V33'),
    (gen_random_uuid(), 'runtime.export.execute', '运行态导出执行', 'WORKSPACE', 'runtime.export', '执行运行态导出', 'FLYWAY_V33')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO plm_platform.platform_role (id, role_code, role_name, role_status, built_in_flag, description, created_by)
VALUES
    (gen_random_uuid(), 'platform_super_admin', '平台超级管理员', 'ACTIVE', TRUE, '拥有全部平台侧控制能力', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform_admin', '平台管理员', 'ACTIVE', TRUE, '拥有主要平台管理能力', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform_operator', '平台运营人员', 'ACTIVE', TRUE, '负责平台日常运营处理', 'FLYWAY_V33'),
    (gen_random_uuid(), 'platform_auditor', '平台审计人员', 'ACTIVE', TRUE, '负责平台审计查看', 'FLYWAY_V33')
ON CONFLICT (role_code) DO NOTHING;