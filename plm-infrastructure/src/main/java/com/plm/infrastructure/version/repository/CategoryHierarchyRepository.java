package com.plm.infrastructure.version.repository;

import com.plm.common.version.domain.CategoryHierarchy;
import com.plm.common.version.domain.CategoryHierarchyId;
import com.plm.common.version.domain.MetaCategoryDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
