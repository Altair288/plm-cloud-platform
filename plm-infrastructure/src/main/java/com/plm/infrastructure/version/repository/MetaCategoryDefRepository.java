package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCategoryDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface MetaCategoryDefRepository extends JpaRepository<MetaCategoryDef, UUID> {
    Optional<MetaCategoryDef> findByCodeKey(String codeKey);
    boolean existsByCodeKey(String codeKey);

    // 批量查询已有的 codeKey，降低并发导入时逐条 exists 造成的竞态/性能问题
    List<MetaCategoryDef> findByCodeKeyIn(Collection<String> codeKeys);
}
