package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaCategoryDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.UUID;

@Repository
public interface MetaAttributeDefRepository extends JpaRepository<MetaAttributeDef, UUID> {
    Optional<MetaAttributeDef> findByCategoryDefAndKey(MetaCategoryDef categoryDef, String key);

    @Query("""
            select d from MetaAttributeDef d
            where d.businessDomain = :businessDomain
              and d.key = :key
              and lower(d.status) <> 'deleted'
            """)
    Optional<MetaAttributeDef> findActiveByBusinessDomainAndKey(@Param("businessDomain") String businessDomain,
                                                                @Param("key") String key);

                @Query("""
                            select d from MetaAttributeDef d
                            join fetch d.categoryDef c
                                                where d.businessDomain = :businessDomain
                                                        and d.key in :keys
                                                        and lower(d.status) <> 'deleted'
                                                """)
                List<MetaAttributeDef> findActiveByBusinessDomainAndKeyIn(@Param("businessDomain") String businessDomain,
                                                                                                                                                                                                                                                        @Param("keys") Collection<String> keys);

    @Query("""
            select d from MetaAttributeDef d
            where d.key = :key
              and lower(d.status) <> 'deleted'
            """)
    List<MetaAttributeDef> findActiveByKey(@Param("key") String key);

    @Query("""
            select d from MetaAttributeDef d
            where d.categoryDef = :categoryDef
              and d.key = :key
              and lower(d.status) <> 'deleted'
            """)
    Optional<MetaAttributeDef> findActiveByCategoryDefAndKey(@Param("categoryDef") MetaCategoryDef categoryDef,
                                                              @Param("key") String key);

    List<MetaAttributeDef> findByCategoryDefAndKeyIn(MetaCategoryDef categoryDef, Collection<String> keys);
    List<MetaAttributeDef> findByCategoryDefIdIn(Collection<UUID> categoryDefIds);

    @Modifying
    @Query(value = "INSERT INTO plm_meta.meta_attribute_def (id, category_def_id, business_domain, key, lov_flag, auto_bind_key, created_by, created_at) " +
            "VALUES (:id, :categoryDefId, :businessDomain, :key, :lovFlag, :autoBindKey, :createdBy, now()) " +
            "ON CONFLICT (business_domain, key) WHERE status <> 'deleted' DO NOTHING", nativeQuery = true)
    int insertIgnore(@Param("id") UUID id,
                     @Param("categoryDefId") UUID categoryDefId,
                     @Param("businessDomain") String businessDomain,
                     @Param("key") String key,
                     @Param("lovFlag") boolean lovFlag,
                     @Param("autoBindKey") String autoBindKey,
                     @Param("createdBy") String createdBy);
}
