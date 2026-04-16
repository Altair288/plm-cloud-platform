ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS source_scene VARCHAR(20) NOT NULL DEFAULT 'WORKSPACE';

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS invitation_channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL';

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS target_role_code VARCHAR(64) NOT NULL DEFAULT 'workspace_member';

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS batch_id UUID;

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS sent_at TIMESTAMPTZ;

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMPTZ;

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS canceled_by_user_id UUID REFERENCES plm_platform.user_account(id);

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(255);

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

ALTER TABLE plm_platform.workspace_invitation
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_workspace_invitation_batch_id
    ON plm_platform.workspace_invitation (batch_id)
    WHERE batch_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS plm_platform.workspace_invitation_link (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID NOT NULL REFERENCES plm_platform.workspace(id) ON DELETE CASCADE,
    invited_by_user_id UUID NOT NULL REFERENCES plm_platform.user_account(id),
    source_scene       VARCHAR(20) NOT NULL DEFAULT 'WORKSPACE',
    link_status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    invitation_token   VARCHAR(128) NOT NULL,
    target_role_code   VARCHAR(64) NOT NULL DEFAULT 'workspace_member',
    max_use_count      INTEGER,
    used_count         INTEGER NOT NULL DEFAULT 0,
    expires_at         TIMESTAMPTZ,
    last_used_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(64),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(64),
    CONSTRAINT uk_workspace_invitation_link_token UNIQUE (invitation_token),
    CONSTRAINT chk_workspace_invitation_link_used_count_non_negative CHECK (used_count >= 0),
    CONSTRAINT chk_workspace_invitation_link_max_use_count_positive CHECK (max_use_count IS NULL OR max_use_count > 0)
);

CREATE INDEX IF NOT EXISTS idx_workspace_invitation_link_workspace_status
    ON plm_platform.workspace_invitation_link (workspace_id, link_status);

CREATE INDEX IF NOT EXISTS idx_workspace_invitation_link_expires_at
    ON plm_platform.workspace_invitation_link (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS plm_platform.workspace_invitation_link_accept_log (
    id                  UUID PRIMARY KEY,
    invitation_link_id  UUID NOT NULL REFERENCES plm_platform.workspace_invitation_link(id) ON DELETE CASCADE,
    accepted_by_user_id UUID NOT NULL REFERENCES plm_platform.user_account(id),
    workspace_member_id UUID NOT NULL REFERENCES plm_platform.workspace_member(id) ON DELETE CASCADE,
    accepted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    accept_ip           VARCHAR(64),
    user_agent          VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_workspace_invitation_link_accept_log_link
    ON plm_platform.workspace_invitation_link_accept_log (invitation_link_id, accepted_at DESC);

CREATE INDEX IF NOT EXISTS idx_workspace_invitation_link_accept_log_user
    ON plm_platform.workspace_invitation_link_accept_log (accepted_by_user_id, accepted_at DESC);