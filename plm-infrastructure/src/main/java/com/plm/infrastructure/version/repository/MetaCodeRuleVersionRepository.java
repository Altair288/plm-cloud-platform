package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCodeRule;
import com.plm.common.version.domain.MetaCodeRuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaCodeRuleVersionRepository extends JpaRepository<MetaCodeRuleVersion, UUID> {
    Optional<MetaCodeRuleVersion> findByCodeRuleAndIsLatestTrue(MetaCodeRule codeRule);

    List<MetaCodeRuleVersion> findByCodeRuleInAndIsLatestTrue(List<MetaCodeRule> codeRules);

    Optional<MetaCodeRuleVersion> findFirstByCodeRuleOrderByVersionNoDesc(MetaCodeRule codeRule);

    List<MetaCodeRuleVersion> findByCodeRuleOrderByVersionNoDesc(MetaCodeRule codeRule);
}