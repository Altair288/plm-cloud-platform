package com.plm.auth.service;

import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkspaceLifecycleService {
    private static final String BUCKET_STATUS_FROZEN = "FROZEN";
    private static final String BUCKET_STATUS_DELETING = "DELETING";
    private static final String BUCKET_STATUS_DELETED = "DELETED";
    private static final String WORKSPACE_LIFECYCLE_STAGE_FROZEN = "FROZEN";
    private static final String WORKSPACE_LIFECYCLE_STAGE_DELETING = "DELETING";

    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserWorkspaceStateService userWorkspaceStateService;
    private final WorkspaceSessionService workspaceSessionService;

    public WorkspaceLifecycleService(WorkspaceAccessService workspaceAccessService,
                                     WorkspaceRepository workspaceRepository,
                                     WorkspaceMemberRepository workspaceMemberRepository,
                                     WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                                     UserAccountRepository userAccountRepository,
                                     UserWorkspaceStateService userWorkspaceStateService,
                                     WorkspaceSessionService workspaceSessionService) {
        this.workspaceAccessService = workspaceAccessService;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
        this.userAccountRepository = userAccountRepository;
        this.userWorkspaceStateService = userWorkspaceStateService;
        this.workspaceSessionService = workspaceSessionService;
    }

    @Transactional
    public void freezeWorkspace(UUID userId, UUID workspaceId) {
        WorkspaceAccessService.WorkspaceAccessContext access = workspaceAccessService.requireWorkspacePermissionInStatuses(
                userId,
                workspaceId,
                AuthDomainConstants.PERMISSION_WORKSPACE_CONFIG_UPDATE,
                Set.of(AuthDomainConstants.WORKSPACE_STATUS_ACTIVE));
        Workspace workspace = access.workspace();
        if (AuthDomainConstants.WORKSPACE_STATUS_FROZEN.equalsIgnoreCase(workspace.getWorkspaceStatus())) {
            return;
        }

        workspace.setWorkspaceStatus(AuthDomainConstants.WORKSPACE_STATUS_FROZEN);
        workspace.setLifecycleStage(WORKSPACE_LIFECYCLE_STAGE_FROZEN);
        workspace.setUpdatedBy(userId.toString());
        workspaceRepository.save(workspace);

        workspaceStorageBucketRepository.findByWorkspaceId(workspaceId)
                .filter(bucket -> !BUCKET_STATUS_DELETED.equalsIgnoreCase(bucket.getBucketStatus()))
                .ifPresent(bucket -> {
                    bucket.setBucketStatus(BUCKET_STATUS_FROZEN);
                    bucket.setFrozenAt(OffsetDateTime.now());
                    bucket.setUpdatedBy(userId.toString());
                    workspaceStorageBucketRepository.save(bucket);
                });

        clearCurrentWorkspaceSessionIfTarget(access.member().getId());
    }

    @Transactional
    public void deleteWorkspace(UUID userId, UUID workspaceId) {
        WorkspaceAccessService.WorkspaceAccessContext access = workspaceAccessService.requireWorkspacePermissionInStatuses(
                userId,
                workspaceId,
                AuthDomainConstants.PERMISSION_WORKSPACE_CONFIG_UPDATE,
                Set.of(AuthDomainConstants.WORKSPACE_STATUS_ACTIVE, AuthDomainConstants.WORKSPACE_STATUS_FROZEN));
        Workspace workspace = access.workspace();
        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspaceId).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();

        workspace.setWorkspaceStatus(AuthDomainConstants.WORKSPACE_STATUS_FROZEN);
        workspace.setLifecycleStage(WORKSPACE_LIFECYCLE_STAGE_DELETING);
        workspace.setUpdatedBy(userId.toString());
        if (bucket == null || BUCKET_STATUS_DELETED.equalsIgnoreCase(bucket.getBucketStatus())) {
            workspace.setWorkspaceStatus(AuthDomainConstants.WORKSPACE_STATUS_DELETED);
            workspace.setLifecycleStage("DELETED");
        }
        workspaceRepository.save(workspace);

        if (bucket != null && !BUCKET_STATUS_DELETED.equalsIgnoreCase(bucket.getBucketStatus())) {
            bucket.setBucketStatus(BUCKET_STATUS_DELETING);
            bucket.setProvisionErrorCode(null);
            bucket.setProvisionErrorMessage(null);
            if (bucket.getFrozenAt() == null) {
                bucket.setFrozenAt(now);
            }
            bucket.setUpdatedBy(userId.toString());
            workspaceStorageBucketRepository.save(bucket);
        }

        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspaceId(workspaceId);
        for (WorkspaceMember member : members) {
            member.setMemberStatus(AuthDomainConstants.WORKSPACE_MEMBER_STATUS_INACTIVE);
            member.setIsDefaultWorkspace(Boolean.FALSE);
            member.setUpdatedAt(now);
            member.setUpdatedBy(userId.toString());
        }
        if (!members.isEmpty()) {
            workspaceMemberRepository.saveAll(members);
        }
        syncAffectedUsers(members);
        clearCurrentWorkspaceSessionIfTarget(access.member().getId());
    }

    private void syncAffectedUsers(List<WorkspaceMember> members) {
        userAccountRepository.findAllById(members.stream().map(WorkspaceMember::getUserId).distinct().toList())
                .forEach(userWorkspaceStateService::syncUserWorkspaceState);
    }

    private void clearCurrentWorkspaceSessionIfTarget(UUID workspaceMemberId) {
        UUID currentWorkspaceMemberId = AuthStpKit.currentWorkspaceMemberIdOrNull();
        if (currentWorkspaceMemberId != null && currentWorkspaceMemberId.equals(workspaceMemberId)) {
            workspaceSessionService.clearCurrentWorkspaceSession();
        }
    }
}