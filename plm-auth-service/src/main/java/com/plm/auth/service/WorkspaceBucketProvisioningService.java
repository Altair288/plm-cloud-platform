package com.plm.auth.service;

import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageGatewayException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class WorkspaceBucketProvisioningService {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ERROR_CLUSTER_NOT_CONFIGURED = "STORAGE_CLUSTER_NOT_CONFIGURED";
    private static final String ERROR_BUCKET_PROVISION_FAILED = "WORKSPACE_BUCKET_PROVISION_FAILED";
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final StorageClusterRepository storageClusterRepository;
    private final StorageGateway storageGateway;
    private final WorkspaceBucketNamingPolicy workspaceBucketNamingPolicy;

    public WorkspaceBucketProvisioningService(WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                                             StorageClusterRepository storageClusterRepository,
                                             StorageGateway storageGateway,
                                             WorkspaceBucketNamingPolicy workspaceBucketNamingPolicy) {
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
        this.storageClusterRepository = storageClusterRepository;
        this.storageGateway = storageGateway;
        this.workspaceBucketNamingPolicy = workspaceBucketNamingPolicy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionBucket(java.util.UUID workspaceStorageBucketId) {
        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findById(workspaceStorageBucketId)
                .orElse(null);
        if (bucket == null || !canProvision(bucket)) {
            return;
        }

        StorageCluster activeCluster = storageClusterRepository.findByStatus("ACTIVE").orElse(null);
        if (activeCluster == null) {
            markFailed(bucket, ERROR_CLUSTER_NOT_CONFIGURED, "active storage cluster is not configured");
            return;
        }

        bucket.setClusterId(activeCluster.getId());
        bucket.setBucketName(workspaceBucketNamingPolicy.generateBucketName(activeCluster.getBucketPrefix(), bucket.getWorkspaceId()));
        bucket.setUpdatedBy(SYSTEM_ACTOR);

        try {
            storageGateway.ensureBucketExists(activeCluster, bucket.getBucketName());
            bucket.setBucketStatus(STATUS_READY);
            bucket.setProvisionedAt(OffsetDateTime.now());
            bucket.setProvisionErrorCode(null);
            bucket.setProvisionErrorMessage(null);
            workspaceStorageBucketRepository.save(bucket);
        } catch (StorageGatewayException ex) {
            markFailed(bucket, ERROR_BUCKET_PROVISION_FAILED, ex.getMessage());
        }
    }

    private boolean canProvision(WorkspaceStorageBucket bucket) {
        return STATUS_PENDING.equalsIgnoreCase(bucket.getBucketStatus())
                || STATUS_FAILED.equalsIgnoreCase(bucket.getBucketStatus());
    }

    private void markFailed(WorkspaceStorageBucket bucket, String errorCode, String errorMessage) {
        bucket.setBucketStatus(STATUS_FAILED);
        bucket.setProvisionErrorCode(errorCode);
        bucket.setProvisionErrorMessage(truncate(errorMessage, 512));
        bucket.setUpdatedBy(SYSTEM_ACTOR);
        workspaceStorageBucketRepository.save(bucket);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}