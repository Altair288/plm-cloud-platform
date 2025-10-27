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
        // 为了确保父节点先处理，可按 depth 升序（如果 depth 为空则按 path 长度或 codeKey 简单排序）
    all.sort(Comparator.comparing((MetaCategoryDef def) -> Optional.ofNullable(def.getDepth()).orElse((short)0))
        .thenComparing(MetaCategoryDef::getCodeKey));

    Map<UUID, List<UUID>> ancestorsCache = new HashMap<>(all.size());
    Map<UUID, MetaCategoryDef> idToDefMap = new HashMap<>(all.size());
    for (MetaCategoryDef d : all) { idToDefMap.put(d.getId(), d); }
        List<CategoryHierarchy> buffer = new ArrayList<>(all.size() * 3);

        for (MetaCategoryDef def : all) {
            // 构建祖先链：父的祖先列表 + 父自身
            List<UUID> ancestors;
            MetaCategoryDef parent = def.getParent();
            if (parent == null) {
                ancestors = Collections.emptyList();
            } else {
                List<UUID> parentAnc = ancestorsCache.getOrDefault(parent.getId(), Collections.emptyList());
                ancestors = new ArrayList<>(parentAnc.size() + 1);
                ancestors.add(parent.getId());
                ancestors.addAll(parentAnc);
            }
            ancestorsCache.put(def.getId(), ancestors);

            // 自闭包行
            CategoryHierarchy self = new CategoryHierarchy();
            self.setAncestorDef(def);
            self.setDescendantDef(def);
            self.setDistance((short)0);
            buffer.add(self);

            // 祖先闭包行（距离 = 索引+1）
            short distance = 1;
            for (UUID ancId : ancestors) {
                // 使用缓存映射避免每次 stream 扫描
                MetaCategoryDef ancDef = idToDefMap.computeIfAbsent(ancId, k -> all.stream().filter(d -> d.getId().equals(k)).findFirst().orElse(null));
                if (ancDef == null) continue; // 防御：缺失父链
                CategoryHierarchy ch = new CategoryHierarchy();
                ch.setAncestorDef(ancDef);
                ch.setDescendantDef(def);
                ch.setDistance(distance);
                buffer.add(ch);
                distance++;
            }
        }

        hierarchyRepository.saveAll(buffer); // 单次批量写入
        Map<String,Object> result = new HashMap<>();
        result.put("definitions", all.size());
        result.put("closureRows", buffer.size());
        result.put("cacheEntries", ancestorsCache.size());
        result.put("strategy", "ancestor_list_dp");
        return result;
    }

    /** 获取某节点所有后代（不含自身） */
    @Transactional(readOnly = true)
    public List<MetaCategoryDef> findDescendants(UUID ancestorId) {
        return hierarchyRepository.findDescendantDefs(ancestorId);
    }
}
