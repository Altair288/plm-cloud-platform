package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaDictionaryScene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaDictionarySceneRepository extends JpaRepository<MetaDictionaryScene, UUID> {

    @Query("""
        select s
        from MetaDictionaryScene s
        where s.locale = :locale
          and lower(coalesce(s.status, '')) <> 'deleted'
          and s.sceneCode = :sceneCode
        """)
    Optional<MetaDictionaryScene> findActiveBySceneCodeAndLocale(
            @Param("sceneCode") String sceneCode,
            @Param("locale") String locale);
}
