package com.plm.auth.service;

import com.plm.auth.config.AuthInvitationProperties;
import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationEmailBatchItemDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationEmailBatchRequestDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationEmailBatchResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationLinkPreviewResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationLinkRequestDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationLinkResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationPreviewResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceInvitation;
import com.plm.common.domain.auth.WorkspaceInvitationLink;
import com.plm.common.domain.auth.WorkspaceInvitationLinkAcceptLog;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.common.domain.auth.WorkspaceMemberRole;
import com.plm.common.domain.auth.WorkspaceRole;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceInvitationLinkAcceptLogRepository;
import com.plm.infrastructure.repository.auth.WorkspaceInvitationLinkRepository;
import com.plm.infrastructure.repository.auth.WorkspaceInvitationRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRoleRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkspaceInvitationService {
    private static final String SYSTEM_EXPIRE_SYNC = "AUTH_INVITATION_EXPIRE_SYNC";

    private final AuthInvitationProperties invitationProperties;
    private final RegisterEmailSender registerEmailSender;
    private final WorkspaceAccessService workspaceAccessService;
    private final WorkspaceInvitationRepository workspaceInvitationRepository;
    private final WorkspaceInvitationLinkRepository workspaceInvitationLinkRepository;
    private final WorkspaceInvitationLinkAcceptLogRepository workspaceInvitationLinkAcceptLogRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceMemberRoleRepository workspaceMemberRoleRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserAccountRepository userAccountRepository;
    private final WorkspaceSessionService workspaceSessionService;
    private final UserWorkspaceStateService userWorkspaceStateService;

    public WorkspaceInvitationService(AuthInvitationProperties invitationProperties,
                                      RegisterEmailSender registerEmailSender,
                                      WorkspaceAccessService workspaceAccessService,
                                      WorkspaceInvitationRepository workspaceInvitationRepository,
                                      WorkspaceInvitationLinkRepository workspaceInvitationLinkRepository,
                                      WorkspaceInvitationLinkAcceptLogRepository workspaceInvitationLinkAcceptLogRepository,
                                      WorkspaceMemberRepository workspaceMemberRepository,
                                      WorkspaceMemberRoleRepository workspaceMemberRoleRepository,
                                      WorkspaceRepository workspaceRepository,
                                      UserAccountRepository userAccountRepository,
                                      WorkspaceSessionService workspaceSessionService,
                                      UserWorkspaceStateService userWorkspaceStateService) {
        this.invitationProperties = invitationProperties;
        this.registerEmailSender = registerEmailSender;
        this.workspaceAccessService = workspaceAccessService;
        this.workspaceInvitationRepository = workspaceInvitationRepository;
        this.workspaceInvitationLinkRepository = workspaceInvitationLinkRepository;
        this.workspaceInvitationLinkAcceptLogRepository = workspaceInvitationLinkAcceptLogRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceMemberRoleRepository = workspaceMemberRoleRepository;
        this.workspaceRepository = workspaceRepository;
        this.userAccountRepository = userAccountRepository;
        this.workspaceSessionService = workspaceSessionService;
        this.userWorkspaceStateService = userWorkspaceStateService;
    }

    @Transactional
    public AuthWorkspaceInvitationEmailBatchResponseDto sendEmailInvitations(UUID operatorUserId,
                                                                             AuthWorkspaceInvitationEmailBatchRequestDto request) {
        WorkspaceAccessService.WorkspaceAccessContext access = workspaceAccessService.requireWorkspacePermission(
                operatorUserId,
                request.getWorkspaceId(),
                AuthDomainConstants.PERMISSION_WORKSPACE_MEMBER_INVITE
        );

        List<String> rawEmails = request.getEmails();
        if (rawEmails == null || rawEmails.isEmpty()) {
            throw new IllegalArgumentException("emails must not be empty");
        }
        if (rawEmails.size() > invitationProperties.getMaxBatchSize()) {
            throw new IllegalArgumentException("emails exceeds max batch size: " + invitationProperties.getMaxBatchSize());
        }

        expireInvitations();

        String sourceScene = normalizeSourceScene(request.getSourceScene());
        WorkspaceRole targetRole = workspaceAccessService.requireActiveRole(
                request.getWorkspaceId(),
                workspaceAccessService.normalizeRoleCode(request.getTargetRoleCode())
        );

        UUID batchId = UUID.randomUUID();
        String operatorEmail = normalizeEmailOrNull(access.user().getEmail());
        OffsetDateTime now = OffsetDateTime.now();
        List<AuthWorkspaceInvitationEmailBatchItemDto> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int successCount = 0;

        for (String rawEmail : rawEmails) {
            String normalizedEmail;
            try {
                normalizedEmail = AuthNormalizer.normalizeEmail(rawEmail);
            } catch (IllegalArgumentException ex) {
                results.add(batchItem(rawEmail, "INVALID_EMAIL", null, ex.getMessage()));
                continue;
            }

            if (!seen.add(normalizedEmail)) {
                results.add(batchItem(normalizedEmail, "DUPLICATE_INPUT", null, "duplicate email in request"));
                continue;
            }
            if (operatorEmail != null && operatorEmail.equalsIgnoreCase(normalizedEmail)) {
                results.add(batchItem(normalizedEmail, "SELF_SKIPPED", null, "cannot invite current user email"));
                continue;
            }
            if (workspaceMemberRepository.existsByWorkspaceIdAndUserEmailAndMemberStatus(
                    request.getWorkspaceId(),
                    normalizedEmail,
                    AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE)) {
                results.add(batchItem(normalizedEmail, "ALREADY_MEMBER", null, "email already belongs to active member"));
                continue;
            }

            Optional<WorkspaceInvitation> pendingInvitation = workspaceInvitationRepository
                    .findTopByWorkspaceIdAndInviteeEmailIgnoreCaseAndInvitationStatusOrderByCreatedAtDesc(
                            request.getWorkspaceId(),
                            normalizedEmail,
                            AuthDomainConstants.INVITATION_STATUS_PENDING
                    );
            if (pendingInvitation.isPresent()) {
                results.add(batchItem(normalizedEmail, "PENDING_EXISTS", pendingInvitation.get().getId(),
                        "pending invitation already exists"));
                continue;
            }

            WorkspaceInvitation invitation = new WorkspaceInvitation();
            invitation.setWorkspaceId(request.getWorkspaceId());
            invitation.setInviteeEmail(normalizedEmail);
            invitation.setInvitedByUserId(operatorUserId);
            invitation.setSourceScene(sourceScene);
            invitation.setInvitationChannel(AuthDomainConstants.INVITATION_CHANNEL_EMAIL);
            invitation.setTargetRoleCode(targetRole.getRoleCode());
            invitation.setBatchId(batchId);
            invitation.setInvitationStatus(AuthDomainConstants.INVITATION_STATUS_PENDING);
            invitation.setInvitationToken(generateToken());
            invitation.setExpiresAt(now.plusSeconds(invitationProperties.getEmailExpireInSeconds()));
            invitation.setCreatedBy(operatorUserId.toString());
            invitation = workspaceInvitationRepository.save(invitation);

            registerEmailSender.sendWorkspaceInvitationEmail(
                    normalizedEmail,
                    access.workspace().getWorkspaceName(),
                    access.user().getDisplayName(),
                    buildEmailAcceptUrl(invitation.getInvitationToken()),
                    invitation.getExpiresAt()
            );

            invitation.setSentAt(OffsetDateTime.now());
            invitation.setUpdatedBy(operatorUserId.toString());
            workspaceInvitationRepository.save(invitation);
            results.add(batchItem(normalizedEmail, "CREATED", invitation.getId(), null));
            successCount++;
        }

        AuthWorkspaceInvitationEmailBatchResponseDto response = new AuthWorkspaceInvitationEmailBatchResponseDto();
        response.setWorkspaceId(request.getWorkspaceId());
        response.setBatchId(batchId);
        response.setSuccessCount(successCount);
        response.setSkippedCount(results.size() - successCount);
        response.setResults(results);
        return response;
    }

    @Transactional
    public AuthWorkspaceInvitationPreviewResponseDto getEmailInvitationPreview(String token) {
        WorkspaceInvitation invitation = loadInvitationByToken(token);
        Workspace workspace = workspaceRepository.findById(invitation.getWorkspaceId())
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));
        UserAccount inviter = userAccountRepository.findById(invitation.getInvitedByUserId()).orElse(null);

        AuthWorkspaceInvitationPreviewResponseDto response = new AuthWorkspaceInvitationPreviewResponseDto();
        response.setWorkspaceId(workspace.getId());
        response.setWorkspaceName(workspace.getWorkspaceName());
        response.setWorkspaceType(workspace.getWorkspaceType());
        response.setInviterDisplayName(inviter == null ? null : inviter.getDisplayName());
        response.setInviteeEmailMasked(maskEmail(invitation.getInviteeEmail()));
        response.setInvitationStatus(invitation.getInvitationStatus());
        response.setSourceScene(invitation.getSourceScene());
        response.setTargetRoleCode(invitation.getTargetRoleCode());
        response.setExpiresAt(invitation.getExpiresAt());
        response.setCanAccept(AuthDomainConstants.INVITATION_STATUS_PENDING.equalsIgnoreCase(invitation.getInvitationStatus())
                && AuthDomainConstants.WORKSPACE_STATUS_ACTIVE.equalsIgnoreCase(workspace.getWorkspaceStatus()));
        return response;
    }

    @Transactional
    public AuthWorkspaceSessionResponseDto acceptEmailInvitation(UUID userId, String token) {
        UserAccount user = workspaceAccessService.requireActiveUser(userId);
        WorkspaceInvitation invitation = loadInvitationByToken(token);
        ensureInvitationAcceptable(invitation);

        String currentEmail = normalizeEmailOrNull(user.getEmail());
        if (currentEmail == null) {
            throw new AuthBusinessException("INVITATION_ACCEPT_EMAIL_REQUIRED", HttpStatus.CONFLICT,
                    "current user email is required to accept email invitation");
        }
        if (!currentEmail.equalsIgnoreCase(invitation.getInviteeEmail())) {
            throw new AuthBusinessException("INVITATION_EMAIL_MISMATCH", HttpStatus.FORBIDDEN,
                    "current user email does not match invitation target");
        }

        Workspace workspace = workspaceAccessService.requireActiveWorkspace(invitation.getWorkspaceId());
        MembershipResult membership = ensureWorkspaceMembership(
                user,
                workspace,
                invitation.getInvitedByUserId(),
                invitation.getTargetRoleCode(),
                AuthDomainConstants.WORKSPACE_JOIN_TYPE_INVITE
        );

        OffsetDateTime now = OffsetDateTime.now();
        invitation.setInvitationStatus(AuthDomainConstants.INVITATION_STATUS_ACCEPTED);
        invitation.setAcceptedByUserId(userId);
        invitation.setAcceptedAt(now);
        invitation.setUpdatedBy(userId.toString());
        workspaceInvitationRepository.save(invitation);

        if (!workspaceMemberRepository.existsByUserIdAndIsDefaultWorkspaceTrue(userId)) {
            workspaceSessionService.markDefaultWorkspace(userId, membership.member());
        }
        userWorkspaceStateService.syncUserWorkspaceState(user);

        List<String> roleCodes = workspaceMemberRoleRepository.findRoleCodesByWorkspaceMemberIdAndRoleStatus(
                membership.member().getId(),
                AuthDomainConstants.WORKSPACE_ROLE_STATUS_ACTIVE
        );
        return workspaceSessionService.openWorkspaceSession(workspace, membership.member(), roleCodes);
    }

    @Transactional
    public List<AuthWorkspaceInvitationDto> listInvitations(UUID operatorUserId, UUID workspaceId, String status) {
        workspaceAccessService.requireWorkspacePermission(
                operatorUserId,
                workspaceId,
                AuthDomainConstants.PERMISSION_WORKSPACE_MEMBER_INVITE
        );
        expireInvitations();

        String normalizedStatus = normalizeInvitationStatusFilter(status);
        List<WorkspaceInvitation> invitations = normalizedStatus == null
                ? workspaceInvitationRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                : workspaceInvitationRepository.findByWorkspaceIdAndInvitationStatusOrderByCreatedAtDesc(workspaceId, normalizedStatus);

        Map<UUID, UserAccount> inviterMap = loadUsersById(invitations.stream().map(WorkspaceInvitation::getInvitedByUserId).toList());
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));

        return invitations.stream()
                .map(invitation -> toInvitationDto(invitation, workspace, inviterMap.get(invitation.getInvitedByUserId())))
                .toList();
    }

    @Transactional
    public AuthWorkspaceInvitationDto cancelInvitation(UUID operatorUserId, UUID invitationId) {
        WorkspaceInvitation invitation = workspaceInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_INVITATION_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace invitation not found"));
        workspaceAccessService.requireWorkspacePermission(
                operatorUserId,
                invitation.getWorkspaceId(),
                AuthDomainConstants.PERMISSION_WORKSPACE_MEMBER_INVITE
        );
        invitation = syncInvitationStatusIfNeeded(invitation);
        if (AuthDomainConstants.INVITATION_STATUS_ACCEPTED.equalsIgnoreCase(invitation.getInvitationStatus())) {
            throw new AuthBusinessException("WORKSPACE_INVITATION_ALREADY_ACCEPTED", HttpStatus.CONFLICT, "workspace invitation already accepted");
        }
        if (AuthDomainConstants.INVITATION_STATUS_PENDING.equalsIgnoreCase(invitation.getInvitationStatus())) {
            invitation.setInvitationStatus(AuthDomainConstants.INVITATION_STATUS_CANCELED);
            invitation.setCanceledAt(OffsetDateTime.now());
            invitation.setCanceledByUserId(operatorUserId);
            invitation.setCancelReason("CANCELED_BY_OPERATOR");
            invitation.setUpdatedBy(operatorUserId.toString());
            invitation = workspaceInvitationRepository.save(invitation);
        }

        Workspace workspace = workspaceRepository.findById(invitation.getWorkspaceId())
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));
        UserAccount inviter = userAccountRepository.findById(invitation.getInvitedByUserId()).orElse(null);
        return toInvitationDto(invitation, workspace, inviter);
    }

    @Transactional
    public AuthWorkspaceInvitationLinkResponseDto createInvitationLink(UUID operatorUserId,
                                                                       AuthWorkspaceInvitationLinkRequestDto request) {
        WorkspaceAccessService.WorkspaceAccessContext access = workspaceAccessService.requireWorkspacePermission(
                operatorUserId,
                request.getWorkspaceId(),
                AuthDomainConstants.PERMISSION_WORKSPACE_MEMBER_INVITE
        );
        WorkspaceRole targetRole = workspaceAccessService.requireActiveRole(
                request.getWorkspaceId(),
                workspaceAccessService.normalizeRoleCode(request.getTargetRoleCode())
        );
        String sourceScene = normalizeSourceScene(request.getSourceScene());
        Integer maxUseCount = normalizeMaxUseCount(request.getMaxUseCount());
        int expiresInHours = normalizeExpiresInHours(request.getExpiresInHours());

        WorkspaceInvitationLink link = new WorkspaceInvitationLink();
        link.setWorkspaceId(request.getWorkspaceId());
        link.setInvitedByUserId(operatorUserId);
        link.setSourceScene(sourceScene);
        link.setLinkStatus(AuthDomainConstants.INVITATION_LINK_STATUS_ACTIVE);
        link.setInvitationToken(generateToken());
        link.setTargetRoleCode(targetRole.getRoleCode());
        link.setMaxUseCount(maxUseCount);
        link.setUsedCount(0);
        link.setExpiresAt(OffsetDateTime.now().plusHours(expiresInHours));
        link.setCreatedBy(operatorUserId.toString());
        link = workspaceInvitationLinkRepository.save(link);

        return toInvitationLinkDto(link, access.workspace());
    }

    @Transactional
    public AuthWorkspaceInvitationLinkPreviewResponseDto getInvitationLinkPreview(String token) {
        WorkspaceInvitationLink link = loadInvitationLinkByToken(token);
        Workspace workspace = workspaceRepository.findById(link.getWorkspaceId())
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));
        UserAccount inviter = userAccountRepository.findById(link.getInvitedByUserId()).orElse(null);

        AuthWorkspaceInvitationLinkPreviewResponseDto response = new AuthWorkspaceInvitationLinkPreviewResponseDto();
        response.setWorkspaceId(workspace.getId());
        response.setWorkspaceName(workspace.getWorkspaceName());
        response.setWorkspaceType(workspace.getWorkspaceType());
        response.setInviterDisplayName(inviter == null ? null : inviter.getDisplayName());
        response.setSourceScene(link.getSourceScene());
        response.setTargetRoleCode(link.getTargetRoleCode());
        response.setExpiresAt(link.getExpiresAt());
        response.setUsedCount(link.getUsedCount());
        response.setMaxUseCount(link.getMaxUseCount());
        response.setStatus(link.getLinkStatus());
        response.setCanAccept(AuthDomainConstants.INVITATION_LINK_STATUS_ACTIVE.equalsIgnoreCase(link.getLinkStatus())
                && AuthDomainConstants.WORKSPACE_STATUS_ACTIVE.equalsIgnoreCase(workspace.getWorkspaceStatus()));
        return response;
    }

    @Transactional
    public AuthWorkspaceSessionResponseDto acceptInvitationLink(UUID userId, String token, HttpServletRequest request) {
        UserAccount user = workspaceAccessService.requireActiveUser(userId);
        WorkspaceInvitationLink link = loadInvitationLinkByToken(token);
        ensureInvitationLinkAcceptable(link);

        Workspace workspace = workspaceAccessService.requireActiveWorkspace(link.getWorkspaceId());
        MembershipResult membership = ensureWorkspaceMembership(
                user,
                workspace,
                link.getInvitedByUserId(),
                link.getTargetRoleCode(),
                AuthDomainConstants.WORKSPACE_JOIN_TYPE_INVITE_LINK
        );

        if (membership.created()) {
            OffsetDateTime now = OffsetDateTime.now();
            link.setUsedCount(link.getUsedCount() == null ? 1 : link.getUsedCount() + 1);
            link.setLastUsedAt(now);
            if (link.getMaxUseCount() != null && link.getUsedCount() >= link.getMaxUseCount()) {
                link.setLinkStatus(AuthDomainConstants.INVITATION_LINK_STATUS_EXPIRED);
            }
            link.setUpdatedBy(userId.toString());
            workspaceInvitationLinkRepository.save(link);

            WorkspaceInvitationLinkAcceptLog acceptLog = new WorkspaceInvitationLinkAcceptLog();
            acceptLog.setInvitationLinkId(link.getId());
            acceptLog.setAcceptedByUserId(userId);
            acceptLog.setWorkspaceMemberId(membership.member().getId());
            acceptLog.setAcceptedAt(now);
            acceptLog.setAcceptIp(request == null ? null : request.getRemoteAddr());
            acceptLog.setUserAgent(request == null ? null : request.getHeader("User-Agent"));
            workspaceInvitationLinkAcceptLogRepository.save(acceptLog);
        }

        if (!workspaceMemberRepository.existsByUserIdAndIsDefaultWorkspaceTrue(userId)) {
            workspaceSessionService.markDefaultWorkspace(userId, membership.member());
        }
        userWorkspaceStateService.syncUserWorkspaceState(user);

        List<String> roleCodes = workspaceMemberRoleRepository.findRoleCodesByWorkspaceMemberIdAndRoleStatus(
                membership.member().getId(),
                AuthDomainConstants.WORKSPACE_ROLE_STATUS_ACTIVE
        );
        return workspaceSessionService.openWorkspaceSession(workspace, membership.member(), roleCodes);
    }

    @Transactional
    public AuthWorkspaceInvitationLinkResponseDto disableInvitationLink(UUID operatorUserId, UUID linkId) {
        WorkspaceInvitationLink link = workspaceInvitationLinkRepository.findById(linkId)
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_INVITATION_LINK_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace invitation link not found"));
        workspaceAccessService.requireWorkspacePermission(
                operatorUserId,
                link.getWorkspaceId(),
                AuthDomainConstants.PERMISSION_WORKSPACE_MEMBER_INVITE
        );
        link = syncInvitationLinkStatusIfNeeded(link);
        if (AuthDomainConstants.INVITATION_LINK_STATUS_ACTIVE.equalsIgnoreCase(link.getLinkStatus())) {
            link.setLinkStatus(AuthDomainConstants.INVITATION_LINK_STATUS_DISABLED);
            link.setUpdatedBy(operatorUserId.toString());
            link = workspaceInvitationLinkRepository.save(link);
        }
        Workspace workspace = workspaceRepository.findById(link.getWorkspaceId())
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace not found"));
        return toInvitationLinkDto(link, workspace);
    }

    private MembershipResult ensureWorkspaceMembership(UserAccount user,
                                                       Workspace workspace,
                                                       UUID invitedByUserId,
                                                       String targetRoleCode,
                                                       String joinType) {
        Optional<WorkspaceMember> existingMember = workspaceMemberRepository.findByUserIdAndWorkspaceId(user.getId(), workspace.getId());
        if (existingMember.isPresent()) {
            WorkspaceMember member = existingMember.get();
            if (!AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getMemberStatus())) {
                throw new AuthBusinessException("WORKSPACE_MEMBER_INACTIVE", HttpStatus.CONFLICT, "workspace member exists but is not active");
            }
            return new MembershipResult(member, false);
        }

        WorkspaceRole targetRole = workspaceAccessService.requireActiveRole(workspace.getId(), targetRoleCode);
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspace.getId());
        member.setUserId(user.getId());
        member.setMemberStatus(AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE);
        member.setJoinType(joinType);
        member.setJoinedAt(OffsetDateTime.now());
        member.setInvitedByUserId(invitedByUserId);
        member.setCreatedBy(user.getId().toString());
        member = workspaceMemberRepository.save(member);

        WorkspaceMemberRole memberRole = new WorkspaceMemberRole();
        memberRole.setWorkspaceMemberId(member.getId());
        memberRole.setWorkspaceRoleId(targetRole.getId());
        memberRole.setAssignedByUserId(invitedByUserId);
        workspaceMemberRoleRepository.save(memberRole);
        return new MembershipResult(member, true);
    }

    private AuthWorkspaceInvitationDto toInvitationDto(WorkspaceInvitation invitation, Workspace workspace, UserAccount inviter) {
        AuthWorkspaceInvitationDto dto = new AuthWorkspaceInvitationDto();
        dto.setId(invitation.getId());
        dto.setWorkspaceId(invitation.getWorkspaceId());
        dto.setWorkspaceName(workspace == null ? null : workspace.getWorkspaceName());
        dto.setInviteeEmail(invitation.getInviteeEmail());
        dto.setInviteeDisplayName(invitation.getInviteeDisplayName());
        dto.setInviterDisplayName(inviter == null ? null : inviter.getDisplayName());
        dto.setInvitationStatus(invitation.getInvitationStatus());
        dto.setSourceScene(invitation.getSourceScene());
        dto.setInvitationChannel(invitation.getInvitationChannel());
        dto.setTargetRoleCode(invitation.getTargetRoleCode());
        dto.setBatchId(invitation.getBatchId());
        dto.setExpiresAt(invitation.getExpiresAt());
        dto.setSentAt(invitation.getSentAt());
        dto.setAcceptedAt(invitation.getAcceptedAt());
        dto.setAcceptedByUserId(invitation.getAcceptedByUserId());
        dto.setCanceledAt(invitation.getCanceledAt());
        dto.setCanceledByUserId(invitation.getCanceledByUserId());
        dto.setCancelReason(invitation.getCancelReason());
        dto.setCreatedAt(invitation.getCreatedAt());
        return dto;
    }

    private AuthWorkspaceInvitationLinkResponseDto toInvitationLinkDto(WorkspaceInvitationLink link, Workspace workspace) {
        AuthWorkspaceInvitationLinkResponseDto dto = new AuthWorkspaceInvitationLinkResponseDto();
        dto.setLinkId(link.getId());
        dto.setWorkspaceId(link.getWorkspaceId());
        dto.setWorkspaceName(workspace == null ? null : workspace.getWorkspaceName());
        dto.setShareUrl(buildLinkAcceptUrl(link.getInvitationToken()));
        dto.setSourceScene(link.getSourceScene());
        dto.setTargetRoleCode(link.getTargetRoleCode());
        dto.setExpiresAt(link.getExpiresAt());
        dto.setUsedCount(link.getUsedCount());
        dto.setMaxUseCount(link.getMaxUseCount());
        dto.setStatus(link.getLinkStatus());
        dto.setCreatedAt(link.getCreatedAt());
        return dto;
    }

    private AuthWorkspaceInvitationEmailBatchItemDto batchItem(String email, String result, UUID invitationId, String message) {
        AuthWorkspaceInvitationEmailBatchItemDto item = new AuthWorkspaceInvitationEmailBatchItemDto();
        item.setEmail(email);
        item.setResult(result);
        item.setInvitationId(invitationId);
        item.setMessage(message);
        return item;
    }

    private Map<UUID, UserAccount> loadUsersById(List<UUID> userIds) {
        Map<UUID, UserAccount> userMap = new LinkedHashMap<>();
        for (UserAccount user : userAccountRepository.findAllById(userIds.stream().filter(id -> id != null).distinct().toList())) {
            userMap.put(user.getId(), user);
        }
        return userMap;
    }

    private WorkspaceInvitation loadInvitationByToken(String token) {
        expireInvitations();
        WorkspaceInvitation invitation = workspaceInvitationRepository.findByInvitationToken(normalizeToken(token))
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_INVITATION_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace invitation not found"));
        return syncInvitationStatusIfNeeded(invitation);
    }

    private WorkspaceInvitationLink loadInvitationLinkByToken(String token) {
        expireInvitationLinks();
        WorkspaceInvitationLink link = workspaceInvitationLinkRepository.findByInvitationToken(normalizeToken(token))
                .orElseThrow(() -> new AuthBusinessException("WORKSPACE_INVITATION_LINK_NOT_FOUND", HttpStatus.NOT_FOUND, "workspace invitation link not found"));
        return syncInvitationLinkStatusIfNeeded(link);
    }

    private void ensureInvitationAcceptable(WorkspaceInvitation invitation) {
        if (AuthDomainConstants.INVITATION_STATUS_ACCEPTED.equalsIgnoreCase(invitation.getInvitationStatus())) {
            throw new AuthBusinessException("WORKSPACE_INVITATION_ALREADY_ACCEPTED", HttpStatus.CONFLICT, "workspace invitation already accepted");
        }
        if (AuthDomainConstants.INVITATION_STATUS_CANCELED.equalsIgnoreCase(invitation.getInvitationStatus())) {
            throw new AuthBusinessException("WORKSPACE_INVITATION_CANCELED", HttpStatus.GONE, "workspace invitation has been canceled");
        }
        if (AuthDomainConstants.INVITATION_STATUS_EXPIRED.equalsIgnoreCase(invitation.getInvitationStatus())) {
            throw new AuthBusinessException("WORKSPACE_INVITATION_EXPIRED", HttpStatus.GONE, "workspace invitation has expired");
        }
    }

    private void ensureInvitationLinkAcceptable(WorkspaceInvitationLink link) {
        if (AuthDomainConstants.INVITATION_LINK_STATUS_DISABLED.equalsIgnoreCase(link.getLinkStatus())) {
            throw new AuthBusinessException("WORKSPACE_INVITATION_LINK_DISABLED", HttpStatus.GONE, "workspace invitation link has been disabled");
        }
        if (AuthDomainConstants.INVITATION_LINK_STATUS_EXPIRED.equalsIgnoreCase(link.getLinkStatus())) {
            throw new AuthBusinessException("WORKSPACE_INVITATION_LINK_EXPIRED", HttpStatus.GONE, "workspace invitation link has expired");
        }
    }

    private void expireInvitations() {
        OffsetDateTime now = OffsetDateTime.now();
        workspaceInvitationRepository.markExpiredInvitations(
                AuthDomainConstants.INVITATION_STATUS_PENDING,
                AuthDomainConstants.INVITATION_STATUS_EXPIRED,
                now,
                now,
                SYSTEM_EXPIRE_SYNC
        );
    }

    private void expireInvitationLinks() {
        OffsetDateTime now = OffsetDateTime.now();
        workspaceInvitationLinkRepository.markExpiredLinks(
                AuthDomainConstants.INVITATION_LINK_STATUS_ACTIVE,
                AuthDomainConstants.INVITATION_LINK_STATUS_EXPIRED,
                now,
                now,
                SYSTEM_EXPIRE_SYNC
        );
    }

    private WorkspaceInvitation syncInvitationStatusIfNeeded(WorkspaceInvitation invitation) {
        if (AuthDomainConstants.INVITATION_STATUS_PENDING.equalsIgnoreCase(invitation.getInvitationStatus())
                && invitation.getExpiresAt() != null
                && !invitation.getExpiresAt().isAfter(OffsetDateTime.now())) {
            invitation.setInvitationStatus(AuthDomainConstants.INVITATION_STATUS_EXPIRED);
            invitation.setUpdatedBy(SYSTEM_EXPIRE_SYNC);
            return workspaceInvitationRepository.save(invitation);
        }
        return invitation;
    }

    private WorkspaceInvitationLink syncInvitationLinkStatusIfNeeded(WorkspaceInvitationLink link) {
        boolean shouldExpire = AuthDomainConstants.INVITATION_LINK_STATUS_ACTIVE.equalsIgnoreCase(link.getLinkStatus())
                && ((link.getExpiresAt() != null && !link.getExpiresAt().isAfter(OffsetDateTime.now()))
                || (link.getMaxUseCount() != null && link.getUsedCount() != null && link.getUsedCount() >= link.getMaxUseCount()));
        if (shouldExpire) {
            link.setLinkStatus(AuthDomainConstants.INVITATION_LINK_STATUS_EXPIRED);
            link.setUpdatedBy(SYSTEM_EXPIRE_SYNC);
            return workspaceInvitationLinkRepository.save(link);
        }
        return link;
    }

    private String normalizeSourceScene(String sourceScene) {
        String normalized = AuthNormalizer.trimToNull(sourceScene);
        if (normalized == null) {
            return AuthDomainConstants.INVITATION_SOURCE_SCENE_WORKSPACE;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!AuthDomainConstants.INVITATION_SOURCE_SCENE_WORKSPACE.equals(normalized)
                && !AuthDomainConstants.INVITATION_SOURCE_SCENE_ONBOARDING.equals(normalized)) {
            throw new IllegalArgumentException("sourceScene is invalid");
        }
        return normalized;
    }

    private String normalizeInvitationStatusFilter(String status) {
        String normalized = AuthNormalizer.trimToNull(status);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!List.of(
                AuthDomainConstants.INVITATION_STATUS_PENDING,
                AuthDomainConstants.INVITATION_STATUS_ACCEPTED,
                AuthDomainConstants.INVITATION_STATUS_EXPIRED,
                AuthDomainConstants.INVITATION_STATUS_CANCELED
        ).contains(normalized)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return normalized;
    }

    private Integer normalizeMaxUseCount(Integer maxUseCount) {
        if (maxUseCount == null) {
            return null;
        }
        if (maxUseCount <= 0) {
            throw new IllegalArgumentException("maxUseCount must be > 0");
        }
        return maxUseCount;
    }

    private int normalizeExpiresInHours(Integer expiresInHours) {
        int effective = expiresInHours == null ? invitationProperties.getLinkExpireInHours() : expiresInHours;
        if (effective <= 0) {
            throw new IllegalArgumentException("expiresInHours must be > 0");
        }
        return effective;
    }

    private String normalizeToken(String token) {
        String normalized = AuthNormalizer.trimToNull(token);
        if (normalized == null) {
            throw new IllegalArgumentException("token is required");
        }
        return normalized;
    }

    private String normalizeEmailOrNull(String email) {
        String value = AuthNormalizer.trimToNull(email);
        if (value == null) {
            return null;
        }
        return AuthNormalizer.normalizeEmail(value);
    }

    private String buildEmailAcceptUrl(String token) {
        return String.format(invitationProperties.getEmailAcceptUrlTemplate(), token);
    }

    private String buildLinkAcceptUrl(String token) {
        return String.format(invitationProperties.getLinkAcceptUrlTemplate(), token);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private String maskEmail(String email) {
        String normalized = normalizeEmailOrNull(email);
        if (normalized == null) {
            return null;
        }
        int atIndex = normalized.indexOf('@');
        if (atIndex <= 1) {
            return "***" + normalized.substring(Math.max(0, atIndex));
        }
        String local = normalized.substring(0, atIndex);
        String domain = normalized.substring(atIndex);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }

    private record MembershipResult(WorkspaceMember member, boolean created) {
    }
}