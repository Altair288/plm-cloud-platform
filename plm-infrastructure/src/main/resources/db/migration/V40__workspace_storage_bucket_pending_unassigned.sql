ALTER TABLE plm_platform.workspace_storage_bucket
    ALTER COLUMN cluster_id DROP NOT NULL,
    ALTER COLUMN bucket_name DROP NOT NULL;