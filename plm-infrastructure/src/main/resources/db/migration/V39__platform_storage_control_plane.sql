CREATE TABLE IF NOT EXISTS plm_platform.storage_cluster (
    id                      UUID PRIMARY KEY,
    cluster_code            VARCHAR(64) NOT NULL,
    provider_type           VARCHAR(20) NOT NULL DEFAULT 'MINIO',
    endpoint                VARCHAR(255) NOT NULL,
    public_endpoint         VARCHAR(255),
    region_name             VARCHAR(64),
    bucket_prefix           VARCHAR(64) NOT NULL,
    test_bucket_name        VARCHAR(128) NOT NULL,
    secret_ref              VARCHAR(255) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    health_status           VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    last_tested_at          TIMESTAMPTZ,
    last_test_status        VARCHAR(20) NOT NULL DEFAULT 'NOT_TESTED',
    last_test_error_code    VARCHAR(64),
    last_test_error_message VARCHAR(512),
    last_checked_at         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              VARCHAR(64),
    updated_at              TIMESTAMPTZ,
    updated_by              VARCHAR(64),
    CONSTRAINT uk_storage_cluster_code UNIQUE (cluster_code),
    CONSTRAINT uk_storage_cluster_test_bucket_name UNIQUE (test_bucket_name)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_storage_cluster_single_active
    ON plm_platform.storage_cluster (status)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_storage_cluster_status
    ON plm_platform.storage_cluster (status);

CREATE INDEX IF NOT EXISTS idx_storage_cluster_health_status
    ON plm_platform.storage_cluster (health_status);

CREATE TABLE IF NOT EXISTS plm_platform.storage_cluster_test_session (
    id                  UUID PRIMARY KEY,
    cluster_id          UUID NOT NULL REFERENCES plm_platform.storage_cluster(id) ON DELETE CASCADE,
    test_bucket_name    VARCHAR(128) NOT NULL,
    object_key          VARCHAR(512) NOT NULL,
    session_status      VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    upload_token        VARCHAR(128) NOT NULL,
    original_file_name  VARCHAR(255) NOT NULL,
    content_type        VARCHAR(128),
    file_size           BIGINT,
    etag                VARCHAR(128),
    created_by_user_id  UUID NOT NULL REFERENCES plm_platform.user_account(id),
    expires_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(64),
    CONSTRAINT uk_storage_cluster_test_session_upload_token UNIQUE (upload_token)
);

CREATE INDEX IF NOT EXISTS idx_storage_cluster_test_session_cluster_status
    ON plm_platform.storage_cluster_test_session (cluster_id, session_status);

CREATE INDEX IF NOT EXISTS idx_storage_cluster_test_session_expire
    ON plm_platform.storage_cluster_test_session (expires_at);

CREATE TABLE IF NOT EXISTS plm_platform.workspace_storage_bucket (
    id                      UUID PRIMARY KEY,
    workspace_id            UUID NOT NULL REFERENCES plm_platform.workspace(id) ON DELETE CASCADE,
    cluster_id              UUID NOT NULL REFERENCES plm_platform.storage_cluster(id),
    bucket_name             VARCHAR(128) NOT NULL,
    bucket_status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    provision_error_code    VARCHAR(64),
    provision_error_message VARCHAR(512),
    quota_bytes             BIGINT,
    used_bytes              BIGINT NOT NULL DEFAULT 0,
    object_count            BIGINT NOT NULL DEFAULT 0,
    provisioned_at          TIMESTAMPTZ,
    frozen_at               TIMESTAMPTZ,
    deleted_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              VARCHAR(64),
    updated_at              TIMESTAMPTZ,
    updated_by              VARCHAR(64),
    CONSTRAINT uk_workspace_storage_bucket_workspace UNIQUE (workspace_id),
    CONSTRAINT uk_workspace_storage_bucket_name UNIQUE (bucket_name)
);

CREATE INDEX IF NOT EXISTS idx_workspace_storage_bucket_status
    ON plm_platform.workspace_storage_bucket (bucket_status);

CREATE INDEX IF NOT EXISTS idx_workspace_storage_bucket_cluster_status
    ON plm_platform.workspace_storage_bucket (cluster_id, bucket_status);

CREATE TABLE IF NOT EXISTS plm_runtime.object_asset (
    id                  UUID PRIMARY KEY,
    workspace_id        UUID NOT NULL REFERENCES plm_platform.workspace(id) ON DELETE CASCADE,
    bucket_id           UUID NOT NULL REFERENCES plm_platform.workspace_storage_bucket(id),
    object_key          VARCHAR(512) NOT NULL,
    object_status       VARCHAR(20) NOT NULL DEFAULT 'PENDING_UPLOAD',
    biz_type            VARCHAR(64) NOT NULL,
    biz_ref_id          UUID,
    original_file_name  VARCHAR(255) NOT NULL,
    content_type        VARCHAR(128),
    file_size           BIGINT,
    etag                VARCHAR(128),
    sha256              VARCHAR(128),
    uploaded_by_user_id UUID NOT NULL REFERENCES plm_platform.user_account(id),
    visibility_scope    VARCHAR(20) NOT NULL DEFAULT 'WORKSPACE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(64),
    CONSTRAINT uk_object_asset_workspace_key UNIQUE (workspace_id, object_key)
);

CREATE INDEX IF NOT EXISTS idx_object_asset_workspace_status
    ON plm_runtime.object_asset (workspace_id, object_status);

CREATE INDEX IF NOT EXISTS idx_object_asset_workspace_biz
    ON plm_runtime.object_asset (workspace_id, biz_type, biz_ref_id);

CREATE INDEX IF NOT EXISTS idx_object_asset_uploaded_by
    ON plm_runtime.object_asset (uploaded_by_user_id);

CREATE TABLE IF NOT EXISTS plm_runtime.object_upload_session (
    id                    UUID PRIMARY KEY,
    object_id             UUID NOT NULL REFERENCES plm_runtime.object_asset(id) ON DELETE CASCADE,
    workspace_id          UUID NOT NULL REFERENCES plm_platform.workspace(id) ON DELETE CASCADE,
    session_status        VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    upload_token          VARCHAR(128) NOT NULL,
    presigned_method      VARCHAR(10) NOT NULL DEFAULT 'PUT',
    expires_at            TIMESTAMPTZ NOT NULL,
    expected_content_type VARCHAR(128),
    expected_max_size     BIGINT,
    created_by_user_id    UUID NOT NULL REFERENCES plm_platform.user_account(id),
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            VARCHAR(64),
    updated_at            TIMESTAMPTZ,
    updated_by            VARCHAR(64),
    CONSTRAINT uk_object_upload_session_upload_token UNIQUE (upload_token)
);

CREATE INDEX IF NOT EXISTS idx_object_upload_session_workspace_status
    ON plm_runtime.object_upload_session (workspace_id, session_status);

CREATE INDEX IF NOT EXISTS idx_object_upload_session_expire
    ON plm_runtime.object_upload_session (expires_at);