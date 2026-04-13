package com.plm.auth.service;

import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthCreateWorkspaceRequestDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Permission;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.common.domain.auth.WorkspaceMemberRole;
import com.plm.common.domain.auth.WorkspaceRole;
import com.plm.common.domain.auth.WorkspaceRolePermission;
import com.plm.infrastructure.repository.auth.PermissionRepository;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRoleRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRoleRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRolePermissionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorkspaceCommandService {
    private final UserAccountRepository userAccountRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRoleRepository workspaceRoleRepository;
    private final WorkspaceMemberRoleRepository workspaceMemberRoleRepository;
    private final PermissionRepository permissionRepository;
    private final WorkspaceRolePermissionRepository workspaceRolePermissionRepository;
    private final WorkspaceSessionService workspaceSessionService;

    public WorkspaceCommandService(UserAccountRepository userAccountRepository,
                                   WorkspaceRepository workspaceRepository,
                                   WorkspaceMemberRepository workspaceMemberRepository,
                                   WorkspaceRoleRepository workspaceRoleRepository,
                                   WorkspaceMemberRoleRepository workspaceMemberRoleRepository,
                                   PermissionRepository permissionRepository,
                                   WorkspaceRolePermissionRepository workspaceRolePermissionRepository,
                                   WorkspaceSessionService workspaceSessionService) {
        this.userAccountRepository = userAccountRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRoleRepository = workspaceRoleRepository;
        this.workspaceMemberRoleRepository = workspaceMemberRoleRepository;
        this.permissionRepository = permissionRepository;
        this.workspaceRolePermissionRepository = workspaceRolePermissionRepository;
        this.workspaceSessionService = workspaceSessionService;
    }

    @Transactional
    public AuthWorkspaceSessionResponseDto createWorkspace(UUID userId, AuthCreateWorkspaceRequestDto request) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new AuthBusinessException("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "user not found"));
        if (!AuthDomainConstants.USER_STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            throw new AuthBusinessException("USER_NOT_ACTIVE", HttpStatus.FORBIDDEN, "user is not active");
        }

        String workspaceName = AuthNormalizer.trimToNull(request.getWorkspaceName());
        String workspaceCode = AuthNormalizer.normalizeWorkspaceCode(request.getWorkspaceCode());
        if (workspaceName == null || workspaceName.length() > 128) {
            throw new IllegalArgumentException("workspaceName is required and must be <= 128 chars");
        }
        if (workspaceRepository.existsByWorkspaceCode(workspaceCode)) {
            throw new AuthBusinessException("WORKSPACE_CODE_ALREADY_EXISTS", HttpStatus.CONFLICT, "workspaceCode already exists");
        }

        Workspace workspace = new Workspace();
        workspace.setWorkspaceCode(workspaceCode);
        workspace.setWorkspaceName(workspaceName);
        workspace.setOwnerUserId(userId);
        workspace.setWorkspaceStatus(AuthDomainConstants.WORKSPACE_STATUS_ACTIVE);
        workspace.setWorkspaceType(defaultValue(request.getWorkspaceType(), "DEFAULT"));
        workspace.setLifecycleStage("ACTIVE");
        workspace.setDefaultLocale(defaultValue(request.getDefaultLocale(), "zh-CN"));
        workspace.setDefaultTimezone(defaultValue(request.getDefaultTimezone(), "Asia/Shanghai"));
        workspace.setCreatedBy(userId.toString());
        workspace = workspaceRepository.save(workspace);

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspace.getId());
        member.setUserId(userId);
        member.setMemberStatus(AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE);
        member.setJoinType(AuthDomainConstants.WORKSPACE_JOIN_TYPE_OWNER);
        member.setJoinedAt(OffsetDateTime.now());
        member.setIsDefaultWorkspace(Boolean.FALSE);
        member.setCreatedBy(userId.toString());
        member = workspaceMemberRepository.save(member);

        List<WorkspaceRole> roles = createBuiltInRoles(workspace.getId(), userId);
        workspaceRoleRepository.saveAll(roles);
        workspaceRolePermissionRepository.saveAll(createBuiltInRolePermissions(roles));

        WorkspaceRole ownerRole = roles.stream()
                .filter(role -> AuthDomainConstants.ROLE_CODE_WORKSPACE_OWNER.equals(role.getRoleCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("workspace owner role not created"));

        WorkspaceMemberRole memberRole = new WorkspaceMemberRole();
        memberRole.setWorkspaceMemberId(member.getId());
        memberRole.setWorkspaceRoleId(ownerRole.getId());
        memberRole.setAssignedByUserId(userId);
        workspaceMemberRoleRepository.save(memberRole);

        boolean shouldRememberAsDefault = Boolean.TRUE.equals(request.getRememberAsDefault())
                || !workspaceMemberRepository.existsByUserIdAndIsDefaultWorkspaceTrue(userId);
        if (shouldRememberAsDefault) {
            workspaceSessionService.markDefaultWorkspace(userId, member);
        }

        return workspaceSessionService.openWorkspaceSession(workspace, member, List.of(AuthDomainConstants.ROLE_CODE_WORKSPACE_OWNER));
    }

    private List<WorkspaceRole> createBuiltInRoles(UUID workspaceId, UUID operatorUserId) {
        List<WorkspaceRole> roles = new ArrayList<>();
        roles.add(builtInRole(workspaceId, AuthDomainConstants.ROLE_CODE_WORKSPACE_OWNER, "空间拥有者", operatorUserId));
        roles.add(builtInRole(workspaceId, AuthDomainConstants.ROLE_CODE_WORKSPACE_ADMIN, "空间管理员", operatorUserId));
        roles.add(builtInRole(workspaceId, AuthDomainConstants.ROLE_CODE_WORKSPACE_MEMBER, "空间成员", operatorUserId));
        roles.add(builtInRole(workspaceId, AuthDomainConstants.ROLE_CODE_WORKSPACE_VIEWER, "空间查看者", operatorUserId));
        return roles;
    }

    private WorkspaceRole builtInRole(UUID workspaceId, String roleCode, String roleName, UUID operatorUserId) {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspaceId);
        role.setRoleCode(roleCode);
        role.setRoleName(roleName);
        role.setRoleType(AuthDomainConstants.WORKSPACE_ROLE_TYPE_SYSTEM);
        role.setRoleStatus(AuthDomainConstants.WORKSPACE_ROLE_STATUS_ACTIVE);
        role.setBuiltInFlag(Boolean.TRUE);
        role.setCreatedBy(operatorUserId.toString());
        return role;
    }

    private List<WorkspaceRolePermission> createBuiltInRolePermissions(List<WorkspaceRole> roles) {
        Map<String, WorkspaceRole> roleMap = roles.stream()
                .collect(Collectors.toMap(WorkspaceRole::getRoleCode, role -> role, (left, right) -> left, LinkedHashMap::new));

        Map<String, List<String>> rolePermissionCodes = Map.of(
                AuthDomainConstants.ROLE_CODE_WORKSPACE_OWNER, List.of(
                        "workspace.member.read",
                        "workspace.member.invite",
                        "workspace.member.disable",
                        "workspace.member.assign-role",
                        "workspace.profile.read",
                        "workspace.profile.update",
                        "workspace.config.read",
                        "workspace.config.update",
                        "runtime.import.execute",
                        "runtime.export.execute"
                ),
                AuthDomainConstants.ROLE_CODE_WORKSPACE_ADMIN, List.of(
                        "workspace.member.read",
                        "workspace.member.invite",
                        "workspace.member.disable",
                        "workspace.member.assign-role",
                        "workspace.profile.read",
                        "workspace.profile.update",
                        "workspace.config.read",
                        "workspace.config.update",
                        "runtime.import.execute",
                        "runtime.export.execute"
                ),
                AuthDomainConstants.ROLE_CODE_WORKSPACE_MEMBER, List.of(
                        "workspace.member.read",
                        "workspace.profile.read",
                        "workspace.config.read",
                        "runtime.import.execute",
                        "runtime.export.execute"
                ),
                AuthDomainConstants.ROLE_CODE_WORKSPACE_VIEWER, List.of(
                        "workspace.member.read",
                        "workspace.profile.read",
                        "workspace.config.read"
                )
        );

        Map<String, Permission> permissionMap = loadPermissions(rolePermissionCodes.values());
        List<WorkspaceRolePermission> bindings = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : rolePermissionCodes.entrySet()) {
            WorkspaceRole role = roleMap.get(entry.getKey());
            if (role == null) {
                continue;
            }
            for (String permissionCode : entry.getValue()) {
                Permission permission = permissionMap.get(permissionCode);
                if (permission == null) {
                    throw new IllegalStateException("permission seed missing: " + permissionCode);
                }
                WorkspaceRolePermission binding = new WorkspaceRolePermission();
                binding.setWorkspaceRoleId(role.getId());
                binding.setPermissionId(permission.getId());
                bindings.add(binding);
            }
        }
        return bindings;
    }

    private Map<String, Permission> loadPermissions(Collection<List<String>> groupedPermissionCodes) {
        List<String> permissionCodes = groupedPermissionCodes.stream()
                .flatMap(Collection::stream)
                .distinct()
                .toList();
        return permissionRepository.findByPermissionCodeIn(permissionCodes)
                .stream()
                .collect(Collectors.toMap(Permission::getPermissionCode, permission -> permission));
    }

    private String defaultValue(String value, String fallback) {
        String normalized = AuthNormalizer.trimToNull(value);
        return normalized == null ? fallback : normalized;
    }
}