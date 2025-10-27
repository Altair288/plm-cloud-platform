package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCategoryDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaCategoryDefRepository extends JpaRepository<MetaCategoryDef, UUID> {
    Optional<MetaCategoryDef> findByCodeKey(String codeKey);
    boolean existsByCodeKey(String codeKey);
}
