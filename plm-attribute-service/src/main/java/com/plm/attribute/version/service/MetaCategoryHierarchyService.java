package com.plm.attribute.version.service;

import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.common.version.domain.CategoryHierarchy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MetaCategoryHierarchyService {
    private final MetaCategoryDefRepository defRepository;
    private final CategoryHierarchyRepository hierarchyRepository;

    public MetaCategoryHierarchyService(MetaCategoryDefRepository defRepository,
                                        CategoryHierarchyRepository hierarchyRepository) {
        this.defRepository = defRepository;
        this.hierarchyRepository = hierarchyRepository;
    }

    /**
     * 重建闭包表：清空现有 category_hierarchy，重新计算所有 ancestor->descendant 关系。
     */
    @Transactional
    public Map<String,Object> rebuildClosure() {
        hierarchyRepository.deleteAll();
        List<MetaCategoryDef> all = defRepository.findAll();
        Map<UUID, MetaCategoryDef> byId = new HashMap<>();
        for (MetaCategoryDef d : all) byId.put(d.getId(), d);

        long rows = 0;
        for (MetaCategoryDef def : all) {
            // self
            CategoryHierarchy self = new CategoryHierarchy();
            self.setAncestorDef(def);
            self.setDescendantDef(def);
            self.setDistance((short)0);
            hierarchyRepository.save(self);
            rows++;
            // ancestors chain
            short distance = 1;
            MetaCategoryDef cursor = def.getParent();
            while (cursor != null) {
                CategoryHierarchy ch = new CategoryHierarchy();
                ch.setAncestorDef(cursor);
                ch.setDescendantDef(def);
                ch.setDistance(distance);
                hierarchyRepository.save(ch);
                rows++;
                cursor = cursor.getParent();
                distance++;
            }
        }
        Map<String,Object> result = new HashMap<>();
        result.put("definitions", all.size());
        result.put("closureRows", rows);
        return result;
    }

    /** 获取某节点所有后代（不含自身） */
    @Transactional(readOnly = true)
    public List<MetaCategoryDef> findDescendants(UUID ancestorId) {
        return hierarchyRepository.findDescendantDefs(ancestorId);
    }
}
