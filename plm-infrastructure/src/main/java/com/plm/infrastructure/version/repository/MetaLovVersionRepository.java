package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaLovVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.UUID;

@Repository
public interface MetaLovVersionRepository extends JpaRepository<MetaLovVersion, UUID> {
    @Query("select v from MetaLovVersion v where v.lovDef = :def and v.isLatest = true")
    Optional<MetaLovVersion> findLatestByDef(@Param("def") MetaLovDef def);

  List<MetaLovVersion> findByLovDefInAndIsLatestTrue(Collection<MetaLovDef> defs);

    @Query(value = """
            select coalesce(sum(jsonb_array_length(coalesce(v.value_json -> 'values', '[]'::jsonb))), 0)
            from plm_meta.meta_lov_version v
            join plm_meta.meta_lov_def d on d.id = v.lov_def_id
            join plm_meta.meta_attribute_def a on a.id = d.attribute_def_id
            join plm_meta.meta_category_def c on c.id = a.category_def_id
            where v.is_latest = true
              and (v.status is null or lower(v.status) <> 'deleted')
              and (d.status is null or lower(d.status) <> 'deleted')
              and (a.status is null or lower(a.status) <> 'deleted')
              and (c.status is null or lower(c.status) <> 'deleted')
              and c.business_domain = :businessDomain
              and c.id in (:categoryDefIds)
            """, nativeQuery = true)
    long countActiveLatestOptionRowsByBusinessDomainAndCategoryDefIds(@Param("businessDomain") String businessDomain,
                                                                      @Param("categoryDefIds") Collection<UUID> categoryDefIds);

    @Modifying
    @Query("""
            update MetaLovVersion v
            set v.status = 'deleted',
                v.isLatest = false
            where v.lovDef in :lovDefs
              and lower(v.status) <> 'deleted'
            """)
    int softDeleteByLovDefs(@Param("lovDefs") Collection<MetaLovDef> lovDefs);
}
