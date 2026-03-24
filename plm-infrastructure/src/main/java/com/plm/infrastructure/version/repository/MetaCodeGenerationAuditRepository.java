package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCodeGenerationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MetaCodeGenerationAuditRepository extends JpaRepository<MetaCodeGenerationAudit, UUID> {
}