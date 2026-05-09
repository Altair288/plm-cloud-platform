package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.UUID;

@Repository
public interface MetaAttributeVersionRepository extends JpaRepository<MetaAttributeVersion, UUID> {
    @Query("select v from MetaAttributeVersion v where v.attributeDef = :def and v.isLatest = true")
    Optional<MetaAttributeVersion> findLatestByDef(@Param("def") MetaAttributeDef def);

    List<MetaAttributeVersion> findByAttributeDefInAndIsLatestTrue(Collection<MetaAttributeDef> defs);

    List<MetaAttributeVersion> findByAttributeDefCategoryDefIdInAndIsLatestTrue(Collection<UUID> categoryDefIds);

        @Modifying
        @Query("""
                        update MetaAttributeVersion v
                        set v.status = 'deleted',
                                v.isLatest = false
                        where v.attributeDef = :def
                            and lower(v.status) <> 'deleted'
                        """)
        int softDeleteByDef(@Param("def") MetaAttributeDef def);
}
