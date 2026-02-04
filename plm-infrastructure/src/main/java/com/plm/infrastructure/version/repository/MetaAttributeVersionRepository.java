package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.common.api.dto.MetaAttributeDefListItemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.UUID;

@Repository
public interface MetaAttributeVersionRepository extends JpaRepository<MetaAttributeVersion, UUID> {
    @Query("select v from MetaAttributeVersion v where v.attributeDef = :def and v.isLatest = true")
    Optional<MetaAttributeVersion> findLatestByDef(@Param("def") MetaAttributeDef def);

        @Query(
                value = """
                        select new com.plm.common.api.dto.MetaAttributeDefListItemDto(
                            d.key,
                            v.lovKey,
                            c.codeKey,
                            d.status,
                            v.versionNo,
                            v.displayName,
                            v.dataType,
                            v.unit,
                            d.lovFlag,
                            d.createdAt
                        )
                        from MetaAttributeVersion v
                        join v.attributeDef d
                        join d.categoryDef c
                        where v.isLatest = true
                            and (:categoryCodePrefix is null or :categoryCodePrefix = '' or c.codeKey like concat(:categoryCodePrefix, '%'))
                            and (:keyword is null or :keyword = '' or v.displayName like concat('%', :keyword, '%'))
                            and (:dataType is null or :dataType = '' or v.dataType = :dataType)
                            and (:requiredFlag is null or v.requiredFlag = :requiredFlag)
                            and (:uniqueFlag is null or v.uniqueFlag = :uniqueFlag)
                            and (:searchableFlag is null or v.searchableFlag = :searchableFlag)
                        """,
                countQuery = """
                        select count(v.id)
                        from MetaAttributeVersion v
                        join v.attributeDef d
                        join d.categoryDef c
                        where v.isLatest = true
                            and (:categoryCodePrefix is null or :categoryCodePrefix = '' or c.codeKey like concat(:categoryCodePrefix, '%'))
                            and (:keyword is null or :keyword = '' or v.displayName like concat('%', :keyword, '%'))
                            and (:dataType is null or :dataType = '' or v.dataType = :dataType)
                            and (:requiredFlag is null or v.requiredFlag = :requiredFlag)
                            and (:uniqueFlag is null or v.uniqueFlag = :uniqueFlag)
                            and (:searchableFlag is null or v.searchableFlag = :searchableFlag)
                        """
        )
        Page<MetaAttributeDefListItemDto> searchLatestListItems(
                @Param("categoryCodePrefix") String categoryCodePrefix,
                @Param("keyword") String keyword,
                @Param("dataType") String dataType,
                @Param("requiredFlag") Boolean requiredFlag,
                @Param("uniqueFlag") Boolean uniqueFlag,
                @Param("searchableFlag") Boolean searchableFlag,
                Pageable pageable
        );
}
