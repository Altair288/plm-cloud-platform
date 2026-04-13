CREATE TABLE IF NOT EXISTS plm_platform.email_verification_code (
    id                    UUID PRIMARY KEY,
    target_email          VARCHAR(128) NOT NULL,
    verification_purpose  VARCHAR(32) NOT NULL,
    code_hash             VARCHAR(255) NOT NULL,
    code_status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at            TIMESTAMPTZ NOT NULL,
    consumed_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            VARCHAR(64),
    updated_at            TIMESTAMPTZ,
    updated_by            VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_email_verification_code_target_purpose_status_created
    ON plm_platform.email_verification_code (lower(target_email), verification_purpose, code_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_email_verification_code_expires_at
    ON plm_platform.email_verification_code (expires_at);