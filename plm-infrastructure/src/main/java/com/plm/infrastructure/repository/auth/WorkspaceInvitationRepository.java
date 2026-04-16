package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {
    Optional<WorkspaceInvitation> findByInvitationToken(String invitationToken);

    Optional<WorkspaceInvitation> findTopByWorkspaceIdAndInviteeEmailIgnoreCaseAndInvitationStatusOrderByCreatedAtDesc(UUID workspaceId,
                                                                                                                       String inviteeEmail,
                                                                                                                       String invitationStatus);

    List<WorkspaceInvitation> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<WorkspaceInvitation> findByWorkspaceIdAndInvitationStatusOrderByCreatedAtDesc(UUID workspaceId, String invitationStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WorkspaceInvitation wi
            set wi.invitationStatus = :expiredStatus,
                wi.updatedAt = :updatedAt,
                wi.updatedBy = :updatedBy
            where wi.invitationStatus = :pendingStatus
              and wi.expiresAt <= :now
            """)
    int markExpiredInvitations(@Param("pendingStatus") String pendingStatus,
                               @Param("expiredStatus") String expiredStatus,
                               @Param("now") OffsetDateTime now,
                               @Param("updatedAt") OffsetDateTime updatedAt,
                               @Param("updatedBy") String updatedBy);
}