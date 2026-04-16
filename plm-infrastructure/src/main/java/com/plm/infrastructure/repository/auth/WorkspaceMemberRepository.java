package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceMember;
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
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {
    List<WorkspaceMember> findByUserIdAndMemberStatusOrderByCreatedAtAsc(UUID userId, String memberStatus);

  long countByUserIdAndMemberStatus(UUID userId, String memberStatus);

    Optional<WorkspaceMember> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    Optional<WorkspaceMember> findByUserIdAndIsDefaultWorkspaceTrue(UUID userId);

    boolean existsByUserIdAndIsDefaultWorkspaceTrue(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WorkspaceMember wm
            set wm.isDefaultWorkspace = false,
                wm.updatedAt = :updatedAt,
                wm.updatedBy = :updatedBy
            where wm.userId = :userId
              and wm.isDefaultWorkspace = true
              and (:excludeId is null or wm.id <> :excludeId)
            """)
    int clearDefaultWorkspace(@Param("userId") UUID userId,
                              @Param("excludeId") UUID excludeId,
                              @Param("updatedAt") OffsetDateTime updatedAt,
                              @Param("updatedBy") String updatedBy);
}