package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.common.version.domain.MetaCategoryDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaCategoryVersionRepository extends JpaRepository<MetaCategoryVersion, UUID> {
    @Query("select v from MetaCategoryVersion v where v.categoryDef = :def and v.isLatest = true")
    Optional<MetaCategoryVersion> findLatestByDef(@Param("def") MetaCategoryDef def);

    List<MetaCategoryVersion> findByCategoryDefOrderByVersionNoAsc(MetaCategoryDef def);

    List<MetaCategoryVersion> findByCategoryDefInAndIsLatestTrue(Collection<MetaCategoryDef> defs);
}
