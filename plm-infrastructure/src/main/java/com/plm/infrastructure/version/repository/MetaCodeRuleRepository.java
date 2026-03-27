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

    List<MetaCodeRule> findAllByCodeIn(List<String> codes);

    Optional<MetaCodeRule> findByBusinessDomainAndTargetType(String businessDomain, String targetType);

    boolean existsByCode(String code);

    boolean existsByBusinessDomainAndTargetType(String businessDomain, String targetType);

    boolean existsByBusinessDomainAndTargetTypeAndCodeNot(String businessDomain, String targetType, String code);

    List<MetaCodeRule> findAllByBusinessDomainOrderByCodeAsc(String businessDomain);

    List<MetaCodeRule> findAllByOrderByCodeAsc();
}