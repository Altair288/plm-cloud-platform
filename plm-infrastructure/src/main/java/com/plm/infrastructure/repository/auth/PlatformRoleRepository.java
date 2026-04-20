package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.PlatformRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlatformRoleRepository extends JpaRepository<PlatformRole, UUID> {
    @Query("select pr from PlatformRole pr where lower(pr.roleCode) = lower(:roleCode)")
    Optional<PlatformRole> findByRoleCodeIgnoreCase(@Param("roleCode") String roleCode);
}