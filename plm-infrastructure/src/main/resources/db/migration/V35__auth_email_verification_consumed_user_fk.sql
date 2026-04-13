ALTER TABLE plm_platform.email_verification_code
    ADD COLUMN IF NOT EXISTS consumed_by_user_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_email_verification_code_consumed_by_user'
    ) THEN
        ALTER TABLE plm_platform.email_verification_code
            ADD CONSTRAINT fk_email_verification_code_consumed_by_user
            FOREIGN KEY (consumed_by_user_id)
            REFERENCES plm_platform.user_account(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_email_verification_code_consumed_by_user
    ON plm_platform.email_verification_code (consumed_by_user_id)
    WHERE consumed_by_user_id IS NOT NULL;