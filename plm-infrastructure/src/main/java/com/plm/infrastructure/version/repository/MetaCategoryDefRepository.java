package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.MetaCategoryDef;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface MetaCategoryDefRepository extends JpaRepository<MetaCategoryDef, UUID> {
    Optional<MetaCategoryDef> findByCodeKey(String codeKey);
    boolean existsByCodeKey(String codeKey);

        List<MetaCategoryDef> findByParentIsNullOrderBySortOrderAscCodeKeyAsc();
        List<MetaCategoryDef> findByParentIdOrderBySortOrderAscCodeKeyAsc(UUID parentId);
        List<MetaCategoryDef> findByParentIdInOrderBySortOrderAscCodeKeyAsc(Collection<UUID> parentIds);

    // 批量查询已有的 codeKey，降低并发导入时逐条 exists 造成的竞态/性能问题
    List<MetaCategoryDef> findByCodeKeyIn(Collection<String> codeKeys);

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
}
