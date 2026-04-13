package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    List<Permission> findByPermissionCodeIn(Collection<String> permissionCodes);
}