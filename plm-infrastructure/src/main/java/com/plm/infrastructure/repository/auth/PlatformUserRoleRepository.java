package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.PlatformUserRole;
import com.plm.common.domain.auth.PlatformUserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PlatformUserRoleRepository extends JpaRepository<PlatformUserRole, PlatformUserRoleId> {
    boolean existsByUserIdAndRoleId(UUID userId, UUID roleId);

    @Query("""
            select pr.roleCode
            from PlatformUserRole pur
            join PlatformRole pr on pr.id = pur.roleId
            where pur.userId = :userId
              and lower(coalesce(pr.roleStatus, '')) = lower(:roleStatus)
            order by pr.roleCode asc
            """)
    List<String> findRoleCodesByUserIdAndRoleStatus(@Param("userId") UUID userId,
                                                    @Param("roleStatus") String roleStatus);

    @Query(value = """
            select distinct p.permission_code
            from plm_platform.platform_user_role pur
            join plm_platform.platform_role pr on pr.id = pur.role_id
            join plm_platform.platform_role_permission prp on prp.role_id = pr.id
            join plm_platform.permission p on p.id = prp.permission_id
            where pur.user_id = :userId
              and lower(coalesce(pr.role_status, '')) = lower(:roleStatus)
            order by p.permission_code asc
            """, nativeQuery = true)
    List<String> findPermissionCodesByUserIdAndRoleStatus(@Param("userId") UUID userId,
                                                          @Param("roleStatus") String roleStatus);
}