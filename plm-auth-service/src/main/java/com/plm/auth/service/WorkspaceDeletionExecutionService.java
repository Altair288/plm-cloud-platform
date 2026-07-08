package com.plm.auth.service;

import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.storage.ObjectAsset;
import com.plm.common.domain.storage.ObjectUploadSession;
import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import com.plm.infrastructure.repository.storage.ObjectAssetRepository;
import com.plm.infrastructure.repository.storage.ObjectUploadSessionRepository;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageGatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceDeletionExecutionService {
    private static final String BUCKET_STATUS_DELETED = "DELETED";
    private static final String BUCKET_STATUS_DELETING = "DELETING";
    private static final String OBJECT_STATUS_DELETED = "DELETED";
    private static final String SESSION_STATUS_CANCELED = "CANCELED";
    private static final String WORKSPACE_LIFECYCLE_STAGE_DELETED = "DELETED";
    private static final String WORKSPACE_LIFECYCLE_STAGE_DELETING = "DELETING";

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final StorageClusterRepository storageClusterRepository;
    private final ObjectAssetRepository objectAssetRepository;
    private final ObjectUploadSessionRepository objectUploadSessionRepository;
    private final StorageGateway storageGateway;

    public WorkspaceDeletionExecutionService(WorkspaceRepository workspaceRepository,
                                             WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                                             StorageClusterRepository storageClusterRepository,
                                             ObjectAssetRepository objectAssetRepository,
                                             ObjectUploadSessionRepository objectUploadSessionRepository,
                                             StorageGateway storageGateway) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
        this.storageClusterRepository = storageClusterRepository;
        this.objectAssetRepository = objectAssetRepository;
        this.objectUploadSessionRepository = objectUploadSessionRepository;
        this.storageGateway = storageGateway;
    }

    @Transactional
    public void executeDeletion(UUID workspaceId, UUID operatorUserId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));
        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspaceId).orElse(null);
        if (bucket == null || BUCKET_STATUS_DELETED.equalsIgnoreCase(bucket.getBucketStatus())) {
            finalizeWorkspaceDeleted(workspace, operatorUserId);
            return;
        }

        StorageCluster cluster = storageClusterRepository.findById(bucket.getClusterId())
                .orElseThrow(() -> new AuthBusinessException("STORAGE_CLUSTER_NOT_CONFIGURED", HttpStatus.CONFLICT,
                        "storage cluster not configured"));
        List<ObjectAsset> liveObjects = objectAssetRepository.findByBucketIdAndObjectStatusNot(bucket.getId(), OBJECT_STATUS_DELETED);
        try {
            deleteWorkspaceObjects(cluster, bucket, liveObjects);
            storageGateway.deleteBucket(cluster, bucket.getBucketName());
        } catch (StorageGatewayException ex) {
            markDeletionFailed(workspace, bucket, operatorUserId, ex.getMessage());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (ObjectAsset objectAsset : liveObjects) {
            objectAsset.setObjectStatus(OBJECT_STATUS_DELETED);
            objectAsset.setUpdatedBy(operatorUserId.toString());
        }
        if (!liveObjects.isEmpty()) {
            objectAssetRepository.saveAll(liveObjects);
        }
        cancelNonTerminalUploadSessions(workspaceId, operatorUserId);

        bucket.setBucketStatus(BUCKET_STATUS_DELETED);
        bucket.setUsedBytes(0L);
        bucket.setObjectCount(0L);
        bucket.setDeletedAt(now);
        bucket.setProvisionErrorCode(null);
        bucket.setProvisionErrorMessage(null);
        bucket.setUpdatedBy(operatorUserId.toString());
        workspaceStorageBucketRepository.save(bucket);

        finalizeWorkspaceDeleted(workspace, operatorUserId, now);
    }

    private void deleteWorkspaceObjects(StorageCluster cluster,
                                        WorkspaceStorageBucket bucket,
                                        List<ObjectAsset> liveObjects) {
        for (ObjectAsset objectAsset : liveObjects) {
            storageGateway.deleteObject(cluster, bucket.getBucketName(), objectAsset.getObjectKey());
        }
    }

    private void cancelNonTerminalUploadSessions(UUID workspaceId, UUID operatorUserId) {
        List<ObjectUploadSession> sessions = objectUploadSessionRepository.findByWorkspaceId(workspaceId);
        if (sessions.isEmpty()) {
            return;
        }
        List<ObjectUploadSession> updatedSessions = new ArrayList<>();
        for (ObjectUploadSession session : sessions) {
            if ("COMPLETED".equalsIgnoreCase(session.getSessionStatus())
                    || "EXPIRED".equalsIgnoreCase(session.getSessionStatus())
                    || SESSION_STATUS_CANCELED.equalsIgnoreCase(session.getSessionStatus())) {
                continue;
            }
            session.setSessionStatus(SESSION_STATUS_CANCELED);
            session.setUpdatedBy(operatorUserId.toString());
            updatedSessions.add(session);
        }
        if (!updatedSessions.isEmpty()) {
            objectUploadSessionRepository.saveAll(updatedSessions);
        }
    }

    private void markDeletionFailed(Workspace workspace,
                                    WorkspaceStorageBucket bucket,
                                    UUID operatorUserId,
                                    String errorMessage) {
        workspace.setWorkspaceStatus(AuthDomainConstants.WORKSPACE_STATUS_FROZEN);
        workspace.setLifecycleStage(WORKSPACE_LIFECYCLE_STAGE_DELETING);
        workspace.setUpdatedBy(operatorUserId.toString());
        workspaceRepository.save(workspace);

        bucket.setBucketStatus(BUCKET_STATUS_DELETING);
        bucket.setProvisionErrorCode("WORKSPACE_BUCKET_DELETE_FAILED");
        bucket.setProvisionErrorMessage(errorMessage);
        bucket.setUpdatedBy(operatorUserId.toString());
        workspaceStorageBucketRepository.save(bucket);
    }

    private void finalizeWorkspaceDeleted(Workspace workspace, UUID operatorUserId) {
        finalizeWorkspaceDeleted(workspace, operatorUserId, OffsetDateTime.now());
    }

    private void finalizeWorkspaceDeleted(Workspace workspace, UUID operatorUserId, OffsetDateTime now) {
        workspace.setWorkspaceStatus(AuthDomainConstants.WORKSPACE_STATUS_DELETED);
        workspace.setLifecycleStage(WORKSPACE_LIFECYCLE_STAGE_DELETED);
        workspace.setUpdatedBy(operatorUserId.toString());
        workspace.setUpdatedAt(now);
        workspaceRepository.save(workspace);
    }
}