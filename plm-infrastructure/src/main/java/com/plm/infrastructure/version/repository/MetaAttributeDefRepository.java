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
    List<MetaAttributeDef> findByCategoryDefAndKeyIn(MetaCategoryDef categoryDef, Collection<String> keys);
    List<MetaAttributeDef> findByCategoryDefIdIn(Collection<UUID> categoryDefIds);

    @Modifying
    @Query(value = "INSERT INTO plm_meta.meta_attribute_def (id, category_def_id, key, lov_flag, auto_bind_key, created_by, created_at) " +
            "VALUES (:id, :categoryDefId, :key, :lovFlag, :autoBindKey, :createdBy, now()) " +
            "ON CONFLICT (category_def_id, key) DO NOTHING", nativeQuery = true)
    int insertIgnore(@Param("id") UUID id,
                     @Param("categoryDefId") UUID categoryDefId,
                     @Param("key") String key,
                     @Param("lovFlag") boolean lovFlag,
                     @Param("autoBindKey") String autoBindKey,
                     @Param("createdBy") String createdBy);
}
