package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaLovVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.UUID;

@Repository
public interface MetaLovVersionRepository extends JpaRepository<MetaLovVersion, UUID> {
    @Query("select v from MetaLovVersion v where v.lovDef = :def and v.isLatest = true")
    Optional<MetaLovVersion> findLatestByDef(@Param("def") MetaLovDef def);
}
