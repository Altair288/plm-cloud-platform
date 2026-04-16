ALTER TABLE plm_platform.user_account
    ADD COLUMN IF NOT EXISTS is_first_login BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE plm_platform.user_account
    ADD COLUMN IF NOT EXISTS workspace_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE plm_platform.user_account
    DROP CONSTRAINT IF EXISTS chk_user_account_workspace_count_non_negative;

ALTER TABLE plm_platform.user_account
    ADD CONSTRAINT chk_user_account_workspace_count_non_negative
    CHECK (workspace_count >= 0);

WITH active_workspace_counts AS (
    SELECT wm.user_id, COUNT(*)::INTEGER AS workspace_count
    FROM plm_platform.workspace_member wm
    WHERE wm.member_status = 'ACTIVE'
    GROUP BY wm.user_id
)
UPDATE plm_platform.user_account ua
SET workspace_count = COALESCE(awc.workspace_count, 0),
    is_first_login = CASE WHEN COALESCE(awc.workspace_count, 0) > 0 THEN FALSE ELSE TRUE END,
    updated_at = now(),
    updated_by = 'FLYWAY_V36'
FROM active_workspace_counts awc
WHERE ua.id = awc.user_id;

UPDATE plm_platform.user_account ua
SET workspace_count = 0,
    is_first_login = TRUE,
    updated_at = now(),
    updated_by = 'FLYWAY_V36'
WHERE NOT EXISTS (
    SELECT 1
    FROM plm_platform.workspace_member wm
    WHERE wm.user_id = ua.id
      AND wm.member_status = 'ACTIVE'
);

CREATE INDEX IF NOT EXISTS idx_user_account_is_first_login
    ON plm_platform.user_account (is_first_login);

CREATE INDEX IF NOT EXISTS idx_user_account_workspace_count
    ON plm_platform.user_account (workspace_count);