package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.WorkspaceMemberRole;
import com.plm.common.domain.auth.WorkspaceMemberRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkspaceMemberRoleRepository extends JpaRepository<WorkspaceMemberRole, WorkspaceMemberRoleId> {
    @Query("""
            select wr.roleCode
            from WorkspaceMemberRole wmr
            join WorkspaceRole wr on wr.id = wmr.workspaceRoleId
            where wmr.workspaceMemberId = :workspaceMemberId
              and lower(coalesce(wr.roleStatus, '')) = lower(:roleStatus)
            order by wr.roleCode asc
            """)
    List<String> findRoleCodesByWorkspaceMemberIdAndRoleStatus(@Param("workspaceMemberId") java.util.UUID workspaceMemberId,
                                                               @Param("roleStatus") String roleStatus);
}