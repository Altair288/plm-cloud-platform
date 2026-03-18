package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.CategoryHierarchy;
import com.plm.common.version.domain.CategoryHierarchyId;
import com.plm.common.version.domain.MetaCategoryDef;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CategoryHierarchyRepository extends JpaRepository<CategoryHierarchy, CategoryHierarchyId> {

    @Query("select h from CategoryHierarchy h where h.ancestorDef.id = :ancestorId")
    List<CategoryHierarchy> findAllByAncestor(@Param("ancestorId") UUID ancestorId);

    @Query("select h.descendantDef from CategoryHierarchy h where h.ancestorDef.id = :ancestorId and h.distance > 0")
    List<MetaCategoryDef> findDescendantDefs(@Param("ancestorId") UUID ancestorId);

    @Query("select count(h) from CategoryHierarchy h where h.ancestorDef.id = :ancestorId and h.distance = 1")
    long countDirectChildren(@Param("ancestorId") UUID ancestorId);

    @Query("select h.descendantDef.id from CategoryHierarchy h where h.ancestorDef.id = :ancestorId")
    List<UUID> findDescendantIdsIncludingSelf(@Param("ancestorId") UUID ancestorId);

        @Query("""
            select h
            from CategoryHierarchy h
            join fetch h.descendantDef d
            left join fetch d.parent p
            where h.ancestorDef.id = :ancestorId
              and (:includeRoot = true or h.distance > 0)
              and (:maxDepth < 0 or h.distance <= :maxDepth)
              and (
                    ((:status is null or :status = '' or lower(:status) = 'all')
                        and (d.status is null or lower(d.status) <> 'deleted'))
                    or
                    ((:status is not null and :status <> '' and lower(:status) <> 'all')
                        and lower(coalesce(d.status, '')) = lower(:status))
              )
            order by h.distance asc, d.sortOrder asc, d.codeKey asc
            """)
        @QueryHints({
            @QueryHint(name = "org.hibernate.readOnly", value = "true"),
            @QueryHint(name = "org.hibernate.fetchSize", value = "512")
        })
        List<CategoryHierarchy> findSubtreeByAncestor(
            @Param("ancestorId") UUID ancestorId,
            @Param("includeRoot") boolean includeRoot,
            @Param("maxDepth") int maxDepth,
            @Param("status") String status,
            Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        delete from CategoryHierarchy h
        where h.descendantDef.id in :descendantIds
          and h.ancestorDef.id not in :internalAncestorIds
        """)
    int deleteExternalLinksForDescendants(
        @Param("descendantIds") List<UUID> descendantIds,
        @Param("internalAncestorIds") List<UUID> internalAncestorIds
    );

    @Query("""
            select h.ancestorDef
            from CategoryHierarchy h
            where h.descendantDef.id = :descendantId
            order by h.distance desc
            """)
    List<MetaCategoryDef> findAncestorsByDescendant(@Param("descendantId") UUID descendantId);

    @Query("""
            select h.ancestorDef.id, count(h)
            from CategoryHierarchy h
            where h.ancestorDef.id in :ancestorIds
                and h.distance = 1
            group by h.ancestorDef.id
            """)
    List<Object[]> countDirectChildrenByAncestorIds(@Param("ancestorIds") List<UUID> ancestorIds);
}
