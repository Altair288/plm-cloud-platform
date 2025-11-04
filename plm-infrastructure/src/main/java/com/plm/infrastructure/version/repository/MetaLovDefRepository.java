package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaAttributeDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.UUID;

@Repository
public interface MetaLovDefRepository extends JpaRepository<MetaLovDef, UUID> {
    List<MetaLovDef> findByKeyIn(Collection<String> keys);
    List<MetaLovDef> findByAttributeDefIn(Collection<MetaAttributeDef> attributeDefs);
    Optional<MetaLovDef> findByKey(String key);

    @Modifying
    @Query(value = "INSERT INTO plm_meta.meta_lov_def (id, attribute_def_id, key, source_attribute_key, description, created_by, created_at) " +
            "VALUES (:id, :attributeDefId, :key, :sourceAttributeKey, :description, :createdBy, now()) " +
            "ON CONFLICT (attribute_def_id, key) DO NOTHING", nativeQuery = true)
    int insertIgnore(@Param("id") UUID id,
                     @Param("attributeDefId") UUID attributeDefId,
                     @Param("key") String key,
                     @Param("sourceAttributeKey") String sourceAttributeKey,
                     @Param("description") String description,
                     @Param("createdBy") String createdBy);
}
