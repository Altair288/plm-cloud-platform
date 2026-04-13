package com.plm.auth.service;

import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.api.dto.auth.AuthMeResponseDto;
import com.plm.common.api.dto.auth.AuthUserSummaryDto;
import com.plm.common.api.dto.auth.AuthWorkspaceOptionDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRoleRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthQueryService {
    private final UserAccountRepository userAccountRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceMemberRoleRepository workspaceMemberRoleRepository;
    private final WorkspaceSessionService workspaceSessionService;

    public AuthQueryService(UserAccountRepository userAccountRepository,
                            WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository workspaceMemberRepository,
                            WorkspaceMemberRoleRepository workspaceMemberRoleRepository,
                            WorkspaceSessionService workspaceSessionService) {
        this.userAccountRepository = userAccountRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceMemberRoleRepository = workspaceMemberRoleRepository;
        this.workspaceSessionService = workspaceSessionService;
    }

    public AuthUserSummaryDto getCurrentUserSummary(UUID userId) {
        UserAccount user = userAccountRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        return toUserSummary(user);
    }

    public List<AuthWorkspaceOptionDto> listWorkspaceOptions(UUID userId) {
        List<WorkspaceMember> members = workspaceMemberRepository.findByUserIdAndMemberStatusOrderByCreatedAtAsc(userId, AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE);
        if (members.isEmpty()) {
            return List.of();
        }
        Map<UUID, Workspace> workspaceMap = workspaceRepository.findAllById(members.stream().map(WorkspaceMember::getWorkspaceId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Workspace::getId, workspace -> workspace, (left, right) -> left, LinkedHashMap::new));

        List<AuthWorkspaceOptionDto> options = new ArrayList<>();
        for (WorkspaceMember member : members) {
            Workspace workspace = workspaceMap.get(member.getWorkspaceId());
            if (workspace == null) {
                continue;
            }
            options.add(toWorkspaceOption(workspace, member));
        }
        return options;
    }

    public AuthWorkspaceOptionDto getDefaultWorkspace(UUID userId) {
        Optional<WorkspaceMember> memberOptional = workspaceMemberRepository.findByUserIdAndIsDefaultWorkspaceTrue(userId);
        if (memberOptional.isEmpty()) {
            return null;
        }
        WorkspaceMember member = memberOptional.get();
        Workspace workspace = workspaceRepository.findById(member.getWorkspaceId()).orElse(null);
        if (workspace == null) {
            return null;
        }
        return toWorkspaceOption(workspace, member);
    }

    public AuthWorkspaceSessionResponseDto getCurrentWorkspaceContext(UUID userId) {
        return workspaceSessionService.getCurrentWorkspaceSession(userId);
    }

    public AuthMeResponseDto getCurrentSession(UUID userId) {
        AuthMeResponseDto response = new AuthMeResponseDto();
        response.setUser(getCurrentUserSummary(userId));
        response.setWorkspaceOptions(listWorkspaceOptions(userId));
        response.setDefaultWorkspace(getDefaultWorkspace(userId));
        response.setCurrentWorkspace(getCurrentWorkspaceContext(userId));
        return response;
    }

    public AuthUserSummaryDto toUserSummary(UserAccount user) {
        AuthUserSummaryDto summary = new AuthUserSummaryDto();
        summary.setId(user.getId());
        summary.setUsername(user.getUsername());
        summary.setDisplayName(user.getDisplayName());
        summary.setEmail(user.getEmail());
        summary.setPhone(user.getPhone());
        summary.setStatus(user.getStatus());
        return summary;
    }

    public AuthWorkspaceOptionDto toWorkspaceOption(Workspace workspace, WorkspaceMember member) {
        AuthWorkspaceOptionDto option = new AuthWorkspaceOptionDto();
        option.setWorkspaceId(workspace.getId());
        option.setWorkspaceCode(workspace.getWorkspaceCode());
        option.setWorkspaceName(workspace.getWorkspaceName());
        option.setWorkspaceStatus(workspace.getWorkspaceStatus());
        option.setWorkspaceMemberId(member.getId());
        option.setMemberStatus(member.getMemberStatus());
        option.setIsDefaultWorkspace(member.getIsDefaultWorkspace());
        return option;
    }
}