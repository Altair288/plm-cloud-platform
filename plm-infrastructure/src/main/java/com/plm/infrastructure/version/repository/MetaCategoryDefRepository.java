package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCategoryDef;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface MetaCategoryDefRepository extends JpaRepository<MetaCategoryDef, UUID> {
    Optional<MetaCategoryDef> findByCodeKey(String codeKey);
    List<MetaCategoryDef> findAllByCodeKey(String codeKey);
    boolean existsByCodeKey(String codeKey);
    Optional<MetaCategoryDef> findByBusinessDomainAndCodeKey(String businessDomain, String codeKey);
    boolean existsByBusinessDomainAndCodeKey(String businessDomain, String codeKey);

    @Query("select d from MetaCategoryDef d where d.businessDomain = :businessDomain and d.codeKey in :codeKeys")
    List<MetaCategoryDef> findByBusinessDomainAndCodeKeyIn(
            @Param("businessDomain") String businessDomain,
            @Param("codeKeys") Collection<String> codeKeys);

        @Query("select d.codeKey from MetaCategoryDef d where d.businessDomain = :businessDomain and d.codeKey like concat(:prefix, '%')")
        List<String> findCodeKeysByBusinessDomainAndCodeKeyPrefix(
            @Param("businessDomain") String businessDomain,
            @Param("prefix") String prefix);

        List<MetaCategoryDef> findByParentIsNullOrderBySortOrderAscCodeKeyAsc();
        List<MetaCategoryDef> findByParentIdOrderBySortOrderAscCodeKeyAsc(UUID parentId);
        List<MetaCategoryDef> findByParentIdInOrderBySortOrderAscCodeKeyAsc(Collection<UUID> parentIds);

        @Query("""
            select count(d)
            from MetaCategoryDef d
            where d.parent.id = :parentId
              and (d.status is null or lower(d.status) <> 'deleted')
            """)
        long countActiveChildren(@Param("parentId") UUID parentId);

        @Query("""
            select coalesce(max(d.sortOrder), 0)
            from MetaCategoryDef d
            where ((:parentId is null and d.parent is null)
                or (:parentId is not null and d.parent.id = :parentId))
              and (d.status is null or lower(d.status) <> 'deleted')
            """)
        Integer findMaxSortByParentId(@Param("parentId") UUID parentId);

        @Query("""
            select d
            from MetaCategoryDef d
            where ((:parentId is null and d.parent is null)
                or (:parentId is not null and d.parent.id = :parentId))
              and (d.status is null or lower(d.status) <> 'deleted')
            order by d.sortOrder asc, d.codeKey asc
            """)
        List<MetaCategoryDef> findActiveSiblingsByParentId(@Param("parentId") UUID parentId);

    // 批量查询已有的 codeKey，降低并发导入时逐条 exists 造成的竞态/性能问题
    List<MetaCategoryDef> findByCodeKeyIn(Collection<String> codeKeys);

        @Query("select min(d.depth) from MetaCategoryDef d where d.parent is null and d.depth is not null")
        Short findMinRootDepth();

        /**
         * UNSPSC 搜索：编码前缀优先 + 名称/全路径名模糊；可选 scopeId 时限定在节点子树内（closure exists）。
         *
         * 注意：这里返回 def 实体，标题由 service 再批量拉取 latest version，避免 N+1。
         */
        @Query("""
                select distinct d
                from MetaCategoryDef d
                left join MetaCategoryVersion v
                    on v.categoryDef = d and v.isLatest = true
                where
                    (
                        d.codeKey like :prefix
                        or lower(coalesce(v.displayName, '')) like lower(:like)
                        or lower(coalesce(d.fullPathName, '')) like lower(:like)
                    )
                    and (:scopeId is null or exists (
                        select 1
                        from CategoryHierarchy h
                        where h.ancestorDef.id = :scopeId
                            and h.descendantDef = d
                    ))
                order by
                    case when d.codeKey like :prefix then 0 else 1 end,
                    coalesce(d.depth, 32767) asc,
                    d.codeKey asc
                """)
        List<MetaCategoryDef> searchUnspsc(
                @Param("prefix") String prefix,
                @Param("like") String like,
                @Param("scopeId") UUID scopeId,
                Pageable pageable
        );

        @Query(
                value = """
                        select d
                        from MetaCategoryDef d
                        left join MetaCategoryVersion v
                            on v.categoryDef = d and v.isLatest = true
                        where
                            d.businessDomain = :businessDomain
                            and
                            ((:parentId is null and d.parent is null)
                                or (:parentId is not null and d.parent.id = :parentId))
                            and (:depth is null or d.depth = :depth)
                            and (
                                ((:status is null or :status = '' or lower(:status) = 'all')
                                    and (d.status is null or lower(d.status) <> 'deleted'))
                                or
                                ((:status is not null and :status <> '' and lower(:status) <> 'all')
                                    and lower(coalesce(d.status, '')) = lower(:status))
                            )
                            and (:keyword is null or :keyword = ''
                                or lower(d.codeKey) like lower(concat('%', :keyword, '%'))
                                or lower(coalesce(v.displayName, '')) like lower(concat('%', :keyword, '%')))
                        order by d.sortOrder asc, d.codeKey asc
                        """,
                countQuery = """
                        select count(d.id)
                        from MetaCategoryDef d
                        left join MetaCategoryVersion v
                            on v.categoryDef = d and v.isLatest = true
                        where
                            d.businessDomain = :businessDomain
                            and
                            ((:parentId is null and d.parent is null)
                                or (:parentId is not null and d.parent.id = :parentId))
                            and (:depth is null or d.depth = :depth)
                            and (
                                ((:status is null or :status = '' or lower(:status) = 'all')
                                    and (d.status is null or lower(d.status) <> 'deleted'))
                                or
                                ((:status is not null and :status <> '' and lower(:status) <> 'all')
                                    and lower(coalesce(d.status, '')) = lower(:status))
                            )
                            and (:keyword is null or :keyword = ''
                                or lower(d.codeKey) like lower(concat('%', :keyword, '%'))
                                or lower(coalesce(v.displayName, '')) like lower(concat('%', :keyword, '%')))
                        """
        )
        Page<MetaCategoryDef> findNodePage(
            @Param("businessDomain") String businessDomain,
                @Param("parentId") UUID parentId,
                @Param("depth") Short depth,
                @Param("status") String status,
                @Param("keyword") String keyword,
                Pageable pageable
        );

        @Query(
                value = """
                        select d
                        from MetaCategoryDef d
                        left join MetaCategoryVersion v
                            on v.categoryDef = d and v.isLatest = true
                        where
                            d.businessDomain = :businessDomain
                            and
                            (
                                ((:status is null or :status = '' or lower(:status) = 'all')
                                    and (d.status is null or lower(d.status) <> 'deleted'))
                                or
                                ((:status is not null and :status <> '' and lower(:status) <> 'all')
                                    and lower(coalesce(d.status, '')) = lower(:status))
                            )
                            and (:keyword is not null and :keyword <> '')
                            and (
                                lower(d.codeKey) like lower(concat('%', :keyword, '%'))
                                or lower(coalesce(v.displayName, '')) like lower(concat('%', :keyword, '%'))
                                or lower(coalesce(d.fullPathName, '')) like lower(concat('%', :keyword, '%'))
                            )
                            and (:scopeId is null or exists (
                                select 1
                                from CategoryHierarchy h
                                where h.ancestorDef.id = :scopeId
                                    and h.descendantDef = d
                            ))
                        order by
                            case when lower(d.codeKey) like lower(concat(:keyword, '%')) then 0 else 1 end,
                            coalesce(d.depth, 32767) asc,
                            d.sortOrder asc,
                            d.codeKey asc
                        """,
                countQuery = """
                        select count(d.id)
                        from MetaCategoryDef d
                        left join MetaCategoryVersion v
                            on v.categoryDef = d and v.isLatest = true
                        where
                            d.businessDomain = :businessDomain
                            and
                            (
                                ((:status is null or :status = '' or lower(:status) = 'all')
                                    and (d.status is null or lower(d.status) <> 'deleted'))
                                or
                                ((:status is not null and :status <> '' and lower(:status) <> 'all')
                                    and lower(coalesce(d.status, '')) = lower(:status))
                            )
                            and (:keyword is not null and :keyword <> '')
                            and (
                                lower(d.codeKey) like lower(concat('%', :keyword, '%'))
                                or lower(coalesce(v.displayName, '')) like lower(concat('%', :keyword, '%'))
                                or lower(coalesce(d.fullPathName, '')) like lower(concat('%', :keyword, '%'))
                            )
                            and (:scopeId is null or exists (
                                select 1
                                from CategoryHierarchy h
                                where h.ancestorDef.id = :scopeId
                                    and h.descendantDef = d
                            ))
                        """
        )
        Page<MetaCategoryDef> searchGeneric(
            @Param("businessDomain") String businessDomain,
                @Param("keyword") String keyword,
                @Param("scopeId") UUID scopeId,
                @Param("status") String status,
                Pageable pageable
        );
}
