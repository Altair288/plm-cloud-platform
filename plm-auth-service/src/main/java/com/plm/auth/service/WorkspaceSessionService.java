package com.plm.auth.service;

import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRoleRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceSessionService {
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceMemberRoleRepository workspaceMemberRoleRepository;

    public WorkspaceSessionService(WorkspaceRepository workspaceRepository,
                                   WorkspaceMemberRepository workspaceMemberRepository,
                                   WorkspaceMemberRoleRepository workspaceMemberRoleRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceMemberRoleRepository = workspaceMemberRoleRepository;
    }

    @Transactional
    public AuthWorkspaceSessionResponseDto switchWorkspace(UUID userId, UUID workspaceId, Boolean rememberAsDefault) {
        WorkspaceMember member = workspaceMemberRepository.findByUserIdAndWorkspaceId(userId, workspaceId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_MEMBER_NOT_FOUND", HttpStatus.FORBIDDEN, "user is not a member of target workspace"));

        Workspace workspace = validateWorkspaceContext(userId, member);

        if (Boolean.TRUE.equals(rememberAsDefault)) {
            markDefaultWorkspace(userId, member);
        }

        List<String> roleCodes = workspaceMemberRoleRepository.findRoleCodesByWorkspaceMemberIdAndRoleStatus(
                member.getId(),
                AuthDomainConstants.WORKSPACE_ROLE_STATUS_ACTIVE
        );
        return openWorkspaceSession(workspace, member, roleCodes);
    }

    @Transactional(readOnly = true)
    public AuthWorkspaceSessionResponseDto getCurrentWorkspaceSession(UUID userId) {
        UUID workspaceMemberId = AuthStpKit.currentWorkspaceMemberIdOrNull();
        if (workspaceMemberId == null) {
            return null;
        }
        WorkspaceMember member = workspaceMemberRepository.findById(workspaceMemberId)
                .orElse(null);
        if (member == null || !userId.equals(member.getUserId())) {
            return null;
        }

        Workspace workspace = validateWorkspaceContext(userId, member);
        List<String> roleCodes = workspaceMemberRoleRepository.findRoleCodesByWorkspaceMemberIdAndRoleStatus(
                member.getId(),
                AuthDomainConstants.WORKSPACE_ROLE_STATUS_ACTIVE
        );
        AuthWorkspaceSessionResponseDto response = new AuthWorkspaceSessionResponseDto();
        response.setWorkspaceToken(AuthStpKit.WORKSPACE.getTokenValue());
        response.setWorkspaceTokenName(AuthStpKit.WORKSPACE.getTokenName());
        response.setWorkspaceId(workspace.getId());
        response.setWorkspaceCode(workspace.getWorkspaceCode());
        response.setWorkspaceName(workspace.getWorkspaceName());
        response.setWorkspaceMemberId(member.getId());
        response.setRoleCodes(roleCodes);
        return response;
    }

    public void clearCurrentWorkspaceSession() {
        AuthStpKit.clearCurrentWorkspaceMemberId();
        if (AuthStpKit.WORKSPACE.isLogin()) {
            AuthStpKit.WORKSPACE.logout();
        }
    }

    @Transactional
    public void markDefaultWorkspace(UUID userId, WorkspaceMember targetMember) {
        OffsetDateTime now = OffsetDateTime.now();
        workspaceMemberRepository.clearDefaultWorkspace(userId, targetMember.getId(), now, userId.toString());
        targetMember.setIsDefaultWorkspace(Boolean.TRUE);
        targetMember.setUpdatedAt(now);
        targetMember.setUpdatedBy(userId.toString());
        workspaceMemberRepository.save(targetMember);
    }

    public AuthWorkspaceSessionResponseDto openWorkspaceSession(Workspace workspace, WorkspaceMember member, List<String> roleCodes) {
        AuthStpKit.bindCurrentWorkspaceMemberId(member.getId());
        if (AuthStpKit.WORKSPACE.isLogin()) {
            AuthStpKit.WORKSPACE.logout();
        }
        AuthStpKit.WORKSPACE.login(member.getId());

        AuthWorkspaceSessionResponseDto response = new AuthWorkspaceSessionResponseDto();
        response.setWorkspaceToken(AuthStpKit.WORKSPACE.getTokenValue());
        response.setWorkspaceTokenName(AuthStpKit.WORKSPACE.getTokenName());
        response.setWorkspaceId(workspace.getId());
        response.setWorkspaceCode(workspace.getWorkspaceCode());
        response.setWorkspaceName(workspace.getWorkspaceName());
        response.setWorkspaceMemberId(member.getId());
        response.setRoleCodes(roleCodes);
        return response;
    }

    private Workspace validateWorkspaceContext(UUID userId, WorkspaceMember member) {
        if (!userId.equals(member.getUserId())) {
            throw new AuthBusinessException("WORKSPACE_MEMBER_MISMATCH", HttpStatus.FORBIDDEN, "workspace member does not belong to current user");
        }
        if (!AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getMemberStatus())) {
            throw new AuthBusinessException("WORKSPACE_MEMBER_INACTIVE", HttpStatus.FORBIDDEN, "workspace member is not active");
        }

        Workspace workspace = workspaceRepository.findById(member.getWorkspaceId())
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));

        if (!AuthDomainConstants.WORKSPACE_STATUS_ACTIVE.equalsIgnoreCase(workspace.getWorkspaceStatus())) {
            throw new AuthBusinessException("WORKSPACE_NOT_ACTIVE", HttpStatus.CONFLICT, "workspace is not active");
        }
        return workspace;
    }
}