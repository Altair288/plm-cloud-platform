package com.plm.auth.service;

import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class WorkspaceDeletionCleanupService {
    private static final String BUCKET_STATUS_DELETING = "DELETING";

    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceDeletionExecutionService workspaceDeletionExecutionService;

    public WorkspaceDeletionCleanupService(WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                                           WorkspaceRepository workspaceRepository,
                                           WorkspaceDeletionExecutionService workspaceDeletionExecutionService) {
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceDeletionExecutionService = workspaceDeletionExecutionService;
    }

    @Scheduled(fixedDelayString = "${plm.storage.workspace.deletion-cleanup-interval-ms:300000}")
    @Transactional
    public void cleanupDeletingWorkspaces() {
        List<WorkspaceStorageBucket> deletingBuckets = workspaceStorageBucketRepository.findByBucketStatusIn(Set.of(BUCKET_STATUS_DELETING));
        for (WorkspaceStorageBucket bucket : deletingBuckets) {
            workspaceRepository.findById(bucket.getWorkspaceId())
                    .ifPresent(workspace -> workspaceDeletionExecutionService.executeDeletion(
                            workspace.getId(),
                            workspace.getOwnerUserId()));
        }
    }
}