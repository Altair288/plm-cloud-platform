CREATE TABLE IF NOT EXISTS plm_platform.workspace_type (
    type_code    VARCHAR(20) PRIMARY KEY,
    label        VARCHAR(64) NOT NULL,
    description  VARCHAR(255),
    sort_order   INTEGER NOT NULL DEFAULT 0,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   VARCHAR(64),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_locale (
    locale_code  VARCHAR(16) PRIMARY KEY,
    label        VARCHAR(64) NOT NULL,
    description  VARCHAR(255),
    sort_order   INTEGER NOT NULL DEFAULT 0,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   VARCHAR(64),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_timezone (
    timezone_code VARCHAR(64) PRIMARY KEY,
    label         VARCHAR(128) NOT NULL,
    description   VARCHAR(255),
    sort_order    INTEGER NOT NULL DEFAULT 0,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    is_default    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(64),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_workspace_type_default
    ON plm_platform.workspace_type (is_default)
    WHERE is_default = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_workspace_locale_default
    ON plm_platform.workspace_locale (is_default)
    WHERE is_default = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_workspace_timezone_default
    ON plm_platform.workspace_timezone (is_default)
    WHERE is_default = TRUE;

INSERT INTO plm_platform.workspace_type (type_code, label, description, sort_order, enabled, is_default, created_by)
VALUES
    ('TEAM', '团队工作区', '管理产品数据、项目目标、团队协作。', 10, TRUE, TRUE, 'FLYWAY_V37'),
    ('PERSONAL', '个人工作区', '管理个人内容，整理思路与资料。', 20, TRUE, FALSE, 'FLYWAY_V37'),
    ('LEARNING', '学习 / 研究', '笔记、研究和知识整理。', 30, TRUE, FALSE, 'FLYWAY_V37')
ON CONFLICT (type_code) DO UPDATE
SET label = EXCLUDED.label,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    is_default = EXCLUDED.is_default,
    updated_at = now(),
    updated_by = 'FLYWAY_V37';

INSERT INTO plm_platform.workspace_locale (locale_code, label, description, sort_order, enabled, is_default, created_by)
VALUES
    ('zh-CN', '简体中文', '默认中文工作区语言。', 10, TRUE, TRUE, 'FLYWAY_V37'),
    ('en-US', 'English', 'Default English workspace locale.', 20, TRUE, FALSE, 'FLYWAY_V37')
ON CONFLICT (locale_code) DO UPDATE
SET label = EXCLUDED.label,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    is_default = EXCLUDED.is_default,
    updated_at = now(),
    updated_by = 'FLYWAY_V37';

INSERT INTO plm_platform.workspace_timezone (timezone_code, label, description, sort_order, enabled, is_default, created_by)
VALUES
    ('Asia/Shanghai', 'Asia/Shanghai (UTC+08:00)', '中国标准时间。', 10, TRUE, TRUE, 'FLYWAY_V37'),
    ('UTC', 'UTC (UTC+00:00)', '协调世界时。', 20, TRUE, FALSE, 'FLYWAY_V37'),
    ('America/Los_Angeles', 'America/Los_Angeles (UTC-08:00)', '北美西海岸时间。', 30, TRUE, FALSE, 'FLYWAY_V37')
ON CONFLICT (timezone_code) DO UPDATE
SET label = EXCLUDED.label,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    is_default = EXCLUDED.is_default,
    updated_at = now(),
    updated_by = 'FLYWAY_V37';

UPDATE plm_platform.workspace
SET workspace_type = 'TEAM',
    updated_at = now(),
    updated_by = 'FLYWAY_V37'
WHERE workspace_type IS NULL
   OR btrim(workspace_type) = ''
   OR upper(workspace_type) = 'DEFAULT'
   OR upper(workspace_type) NOT IN ('TEAM', 'PERSONAL', 'LEARNING');

UPDATE plm_platform.workspace
SET default_locale = 'zh-CN',
    updated_at = now(),
    updated_by = 'FLYWAY_V37'
WHERE default_locale IS NULL
   OR btrim(default_locale) = ''
   OR default_locale NOT IN ('zh-CN', 'en-US');

UPDATE plm_platform.workspace
SET default_timezone = 'Asia/Shanghai',
    updated_at = now(),
    updated_by = 'FLYWAY_V37'
WHERE default_timezone IS NULL
   OR btrim(default_timezone) = ''
   OR default_timezone NOT IN ('Asia/Shanghai', 'UTC', 'America/Los_Angeles');

ALTER TABLE plm_platform.workspace
    DROP CONSTRAINT IF EXISTS fk_workspace_type_code;

ALTER TABLE plm_platform.workspace
    ADD CONSTRAINT fk_workspace_type_code
    FOREIGN KEY (workspace_type) REFERENCES plm_platform.workspace_type(type_code);

ALTER TABLE plm_platform.workspace
    DROP CONSTRAINT IF EXISTS fk_workspace_locale_code;

ALTER TABLE plm_platform.workspace
    ADD CONSTRAINT fk_workspace_locale_code
    FOREIGN KEY (default_locale) REFERENCES plm_platform.workspace_locale(locale_code);

ALTER TABLE plm_platform.workspace
    DROP CONSTRAINT IF EXISTS fk_workspace_timezone_code;

ALTER TABLE plm_platform.workspace
    ADD CONSTRAINT fk_workspace_timezone_code
    FOREIGN KEY (default_timezone) REFERENCES plm_platform.workspace_timezone(timezone_code);