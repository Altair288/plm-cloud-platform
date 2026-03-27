package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCodeRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaCodeRuleSetRepository extends JpaRepository<MetaCodeRuleSet, UUID> {
    Optional<MetaCodeRuleSet> findByBusinessDomain(String businessDomain);

    boolean existsByBusinessDomain(String businessDomain);

    List<MetaCodeRuleSet> findAllByOrderByBusinessDomainAsc();
}