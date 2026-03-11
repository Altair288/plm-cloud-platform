package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaDictionaryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MetaDictionaryItemRepository extends JpaRepository<MetaDictionaryItem, UUID> {

    @Query("""
        select i
        from MetaDictionaryItem i
        where i.dictionaryDef.id = :defId
          and (:includeDisabled = true or i.enabled = true)
        order by i.sortOrder asc, i.itemKey asc
        """)
    List<MetaDictionaryItem> findByDictionaryDefIdOrderBySort(
            @Param("defId") UUID dictionaryDefId,
            @Param("includeDisabled") boolean includeDisabled);
}
