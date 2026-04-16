package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceInvitationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceInvitationLinkRepository extends JpaRepository<WorkspaceInvitationLink, UUID> {
    Optional<WorkspaceInvitationLink> findByInvitationToken(String invitationToken);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WorkspaceInvitationLink wil
            set wil.linkStatus = :expiredStatus,
                wil.updatedAt = :updatedAt,
                wil.updatedBy = :updatedBy
            where wil.linkStatus = :activeStatus
              and ((wil.expiresAt is not null and wil.expiresAt <= :now)
                or (wil.maxUseCount is not null and wil.usedCount >= wil.maxUseCount))
            """)
    int markExpiredLinks(@Param("activeStatus") String activeStatus,
                         @Param("expiredStatus") String expiredStatus,
                         @Param("now") OffsetDateTime now,
                         @Param("updatedAt") OffsetDateTime updatedAt,
                         @Param("updatedBy") String updatedBy);
}