package com.plm.auth.service;

import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WorkspaceStorageBootstrapService {
    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final WorkspaceBucketProvisioningService workspaceBucketProvisioningService;

    public WorkspaceStorageBootstrapService(WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                       WorkspaceBucketProvisioningService workspaceBucketProvisioningService) {
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
    this.workspaceBucketProvisioningService = workspaceBucketProvisioningService;
    }

    @Transactional
    public void registerPendingBucket(Workspace workspace, String createdBy) {
    WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspace.getId())
        .orElseGet(() -> workspaceStorageBucketRepository.save(newPendingBucket(workspace, createdBy)));
    triggerProvisionAfterCommit(bucket.getId());
    }

    private WorkspaceStorageBucket newPendingBucket(Workspace workspace, String createdBy) {
        WorkspaceStorageBucket bucket = new WorkspaceStorageBucket();
        bucket.setWorkspaceId(workspace.getId());
        bucket.setBucketStatus("PENDING");
        bucket.setCreatedBy(createdBy);
        bucket.setUpdatedBy(createdBy);
        return bucket;
    }

    private void triggerProvisionAfterCommit(java.util.UUID workspaceStorageBucketId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            workspaceBucketProvisioningService.provisionBucket(workspaceStorageBucketId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workspaceBucketProvisioningService.provisionBucket(workspaceStorageBucketId);
            }
        });
    }
}