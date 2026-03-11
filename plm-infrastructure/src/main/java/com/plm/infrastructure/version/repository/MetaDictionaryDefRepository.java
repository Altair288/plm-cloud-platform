package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaDictionaryDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaDictionaryDefRepository extends JpaRepository<MetaDictionaryDef, UUID> {

    @Query("""
        select d
        from MetaDictionaryDef d
        where d.locale = :locale
          and lower(coalesce(d.status, '')) <> 'deleted'
          and d.dictCode in :codes
        """)
    List<MetaDictionaryDef> findByDictCodeInAndLocale(
            @Param("codes") Collection<String> codes,
            @Param("locale") String locale);

    @Query("""
        select d
        from MetaDictionaryDef d
        where d.locale = :locale
          and lower(coalesce(d.status, '')) <> 'deleted'
          and d.dictCode = :code
        """)
    Optional<MetaDictionaryDef> findActiveByDictCodeAndLocale(
            @Param("code") String code,
            @Param("locale") String locale);
}
