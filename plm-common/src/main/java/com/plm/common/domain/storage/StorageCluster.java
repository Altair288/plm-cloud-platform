package com.plm.common.domain.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "storage_cluster", schema = "plm_platform")
@Getter
@Setter
public class StorageCluster {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cluster_code", nullable = false, length = 64)
    private String clusterCode;

    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType = "MINIO";

    @Column(name = "endpoint", nullable = false, length = 255)
    private String endpoint;

    @Column(name = "public_endpoint", length = 255)
    private String publicEndpoint;

    @Column(name = "region_name", length = 64)
    private String regionName;

    @Column(name = "bucket_prefix", nullable = false, length = 64)
    private String bucketPrefix;

    @Column(name = "test_bucket_name", nullable = false, length = 128)
    private String testBucketName;

    @Column(name = "secret_ref", nullable = false, length = 255)
    private String secretRef;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "INACTIVE";

    @Column(name = "health_status", nullable = false, length = 20)
    private String healthStatus = "UNKNOWN";

    @Column(name = "last_tested_at")
    private OffsetDateTime lastTestedAt;

    @Column(name = "last_test_status", nullable = false, length = 20)
    private String lastTestStatus = "NOT_TESTED";

    @Column(name = "last_test_error_code", length = 64)
    private String lastTestErrorCode;

    @Column(name = "last_test_error_message", length = 512)
    private String lastTestErrorMessage;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (providerType == null || providerType.isBlank()) {
            providerType = "MINIO";
        }
        if (status == null || status.isBlank()) {
            status = "INACTIVE";
        }
        if (healthStatus == null || healthStatus.isBlank()) {
            healthStatus = "UNKNOWN";
        }
        if (lastTestStatus == null || lastTestStatus.isBlank()) {
            lastTestStatus = "NOT_TESTED";
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}