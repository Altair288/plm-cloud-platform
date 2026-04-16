package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceInvitationLinkAcceptLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WorkspaceInvitationLinkAcceptLogRepository extends JpaRepository<WorkspaceInvitationLinkAcceptLog, UUID> {
}