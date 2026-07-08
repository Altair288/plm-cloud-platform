package com.plm.auth.service;

import com.plm.auth.exception.AuthBusinessException;
import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WorkspaceStorageContextResolver {
    private static final String BUCKET_STATUS_READY = "READY";
    private static final String BUCKET_STATUS_FAILED = "FAILED";
    private static final String BUCKET_STATUS_FROZEN = "FROZEN";

    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final StorageClusterRepository storageClusterRepository;

    public WorkspaceStorageContextResolver(WorkspaceAccessService workspaceAccessService,
                                           WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                                           StorageClusterRepository storageClusterRepository) {
        this.workspaceAccessService = workspaceAccessService;
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
        this.storageClusterRepository = storageClusterRepository;
    }

    @Transactional(readOnly = true)
    public ResolvedWorkspaceStorage requireWritableStorage(UUID userId, UUID workspaceId, String permissionCode) {
        return requireStorage(userId, workspaceId, permissionCode, true);
    }

    @Transactional(readOnly = true)
    public ResolvedWorkspaceStorage requireAccessibleStorage(UUID userId, UUID workspaceId, String permissionCode) {
        return requireStorage(userId, workspaceId, permissionCode, false);
    }

    private ResolvedWorkspaceStorage requireStorage(UUID userId,
                                                    UUID workspaceId,
                                                    String permissionCode,
                                                    boolean writable) {
        WorkspaceAccessService.WorkspaceAccessContext accessContext = workspaceAccessService.requireWorkspacePermission(
                userId,
                workspaceId,
                permissionCode);
        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_BUCKET_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "workspace bucket not found"));
        validateBucketState(bucket, writable);
        StorageCluster cluster = storageClusterRepository.findById(bucket.getClusterId())
                .orElseThrow(() -> new AuthBusinessException("STORAGE_CLUSTER_NOT_CONFIGURED", HttpStatus.CONFLICT,
                        "storage cluster not configured"));
        return new ResolvedWorkspaceStorage(accessContext, bucket, cluster);
    }

    private void validateBucketState(WorkspaceStorageBucket bucket, boolean writable) {
        if (BUCKET_STATUS_READY.equalsIgnoreCase(bucket.getBucketStatus())) {
            return;
        }
        if (BUCKET_STATUS_FAILED.equalsIgnoreCase(bucket.getBucketStatus())) {
            throw new AuthBusinessException("WORKSPACE_BUCKET_PROVISION_FAILED", HttpStatus.CONFLICT,
                    "workspace bucket provisioning failed");
        }
        if (BUCKET_STATUS_FROZEN.equalsIgnoreCase(bucket.getBucketStatus())) {
            if (!writable) {
                return;
            }
            throw new AuthBusinessException("WORKSPACE_BUCKET_NOT_READY", HttpStatus.CONFLICT,
                    "workspace bucket is frozen");
        }
        throw new AuthBusinessException("WORKSPACE_BUCKET_NOT_READY", HttpStatus.CONFLICT,
                "workspace bucket is not ready");
    }

    public record ResolvedWorkspaceStorage(WorkspaceAccessService.WorkspaceAccessContext accessContext,
                                           WorkspaceStorageBucket bucket,
                                           StorageCluster cluster) {
    }
}