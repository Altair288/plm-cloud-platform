package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCodeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaCodeRuleRepository extends JpaRepository<MetaCodeRule, UUID> {
    Optional<MetaCodeRule> findByCode(String code);

    boolean existsByCode(String code);

    List<MetaCodeRule> findAllByOrderByCodeAsc();
}