package com.plm.auth.service;

import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.common.domain.auth.WorkspaceRole;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRolePermissionRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRoleRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkspaceAccessService {
    private final UserAccountRepository userAccountRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRoleRepository workspaceRoleRepository;
    private final WorkspaceRolePermissionRepository workspaceRolePermissionRepository;

    public WorkspaceAccessService(UserAccountRepository userAccountRepository,
                                  WorkspaceRepository workspaceRepository,
                                  WorkspaceMemberRepository workspaceMemberRepository,
                                  WorkspaceRoleRepository workspaceRoleRepository,
                                  WorkspaceRolePermissionRepository workspaceRolePermissionRepository) {
        this.userAccountRepository = userAccountRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRoleRepository = workspaceRoleRepository;
        this.workspaceRolePermissionRepository = workspaceRolePermissionRepository;
    }

    @Transactional(readOnly = true)
    public UserAccount requireActiveUser(UUID userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new AuthBusinessException("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "user not found"));
        if (!AuthDomainConstants.USER_STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            throw new AuthBusinessException("USER_NOT_ACTIVE", HttpStatus.FORBIDDEN, "user is not active");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public Workspace requireActiveWorkspace(UUID workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));
        if (!AuthDomainConstants.WORKSPACE_STATUS_ACTIVE.equalsIgnoreCase(workspace.getWorkspaceStatus())) {
            throw new AuthBusinessException("WORKSPACE_NOT_ACTIVE", HttpStatus.CONFLICT, "workspace is not active");
        }
        return workspace;
    }

    @Transactional(readOnly = true)
    public WorkspaceMember requireActiveWorkspaceMember(UUID userId, UUID workspaceId) {
        WorkspaceMember member = workspaceMemberRepository.findByUserIdAndWorkspaceId(userId, workspaceId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_MEMBER_NOT_FOUND", HttpStatus.FORBIDDEN, "user is not a member of target workspace"));
        if (!AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getMemberStatus())) {
            throw new AuthBusinessException("WORKSPACE_MEMBER_INACTIVE", HttpStatus.FORBIDDEN, "workspace member is not active");
        }
        return member;
    }

    @Transactional(readOnly = true)
    public WorkspaceRole requireActiveRole(UUID workspaceId, String roleCode) {
        String normalizedRoleCode = normalizeRoleCode(roleCode);
        WorkspaceRole role = workspaceRoleRepository.findByWorkspaceIdAndRoleCode(workspaceId, normalizedRoleCode)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_ROLE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace role not found"));
        if (!AuthDomainConstants.WORKSPACE_ROLE_STATUS_ACTIVE.equalsIgnoreCase(role.getRoleStatus())) {
            throw new AuthBusinessException("WORKSPACE_ROLE_INACTIVE", HttpStatus.CONFLICT, "workspace role is not active");
        }
        return role;
    }

    @Transactional(readOnly = true)
    public WorkspaceAccessContext requireWorkspacePermission(UUID userId, UUID workspaceId, String permissionCode) {
        UserAccount user = requireActiveUser(userId);
        Workspace workspace = requireActiveWorkspace(workspaceId);
        WorkspaceMember member = requireActiveWorkspaceMember(userId, workspaceId);
        List<String> permissionCodes = workspaceRolePermissionRepository.findPermissionCodesByWorkspaceMemberId(member.getId());
        if (permissionCode != null && permissionCodes.stream().noneMatch(code -> code.equalsIgnoreCase(permissionCode))) {
            throw new AuthBusinessException("WORKSPACE_PERMISSION_DENIED", HttpStatus.FORBIDDEN,
                    "missing workspace permission: " + permissionCode);
        }
        return new WorkspaceAccessContext(user, workspace, member, permissionCodes);
    }

    public String normalizeRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return AuthDomainConstants.ROLE_CODE_WORKSPACE_MEMBER;
        }
        return roleCode.trim().toLowerCase(Locale.ROOT);
    }

    public record WorkspaceAccessContext(UserAccount user,
                                         Workspace workspace,
                                         WorkspaceMember member,
                                         List<String> permissionCodes) {
    }
}