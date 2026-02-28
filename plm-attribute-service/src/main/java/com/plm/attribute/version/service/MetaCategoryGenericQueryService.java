package com.plm.attribute.version.service;

import com.plm.common.api.dto.*;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MetaCategoryGenericQueryService {

    private enum Taxonomy {
        UNSPSC
    }

    private static final String SUPPORTED_TAXONOMY = Taxonomy.UNSPSC.name();
    private final MetaCategoryDefRepository defRepository;
    private final MetaCategoryVersionRepository versionRepository;
    private final CategoryHierarchyRepository hierarchyRepository;

    public MetaCategoryGenericQueryService(MetaCategoryDefRepository defRepository,
                                           MetaCategoryVersionRepository versionRepository,
                                           CategoryHierarchyRepository hierarchyRepository) {
        this.defRepository = defRepository;
        this.versionRepository = versionRepository;
        this.hierarchyRepository = hierarchyRepository;
    }

    public Page<MetaCategoryNodeDto> listNodes(String taxonomy,
                                               UUID parentId,
                                               Integer level,
                                               String keyword,
                                               String status,
                                               Pageable pageable) {
        requireSupportedTaxonomy(taxonomy);
        short rootDepthBase = resolveRootDepthBase();
        Short depth = level == null ? null : toDepth(level, rootDepthBase);
        Page<MetaCategoryDef> page = defRepository.findNodePage(
                parentId,
                depth,
                normalizeStatus(status),
                trimToNull(keyword),
                pageable);

        Map<UUID, String> titleById = loadLatestTitles(page.getContent());
        Map<UUID, Long> childCountById = loadDirectChildCounts(page.getContent());
        return page.map(def -> toNodeDto(def, titleById.get(def.getId()), childCountById, rootDepthBase));
    }

    public List<MetaCategoryNodeDto> path(UUID id, String taxonomy) {
        requireSupportedTaxonomy(taxonomy);
        MetaCategoryDef target = defRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("category not found: id=" + id));

        List<MetaCategoryDef> defs = hierarchyRepository.findAncestorsByDescendant(target.getId());
        if (defs.isEmpty()) {
            defs = List.of(target);
        }
        short rootDepthBase = resolveRootDepthBase();
        Map<UUID, String> titleById = loadLatestTitles(defs);
        Map<UUID, Long> childCountById = loadDirectChildCounts(defs);
        return defs.stream().map(d -> toNodeDto(d, titleById.get(d.getId()), childCountById, rootDepthBase)).toList();
    }

    public Page<MetaCategorySearchItemDto> search(String taxonomy,
                                                  String keyword,
                                                  UUID scopeNodeId,
                                                  Integer maxDepth,
                                                  String status,
                                                  Pageable pageable) {
        requireSupportedTaxonomy(taxonomy);
        String kw = trimToNull(keyword);
        if (kw == null) {
            return Page.empty(pageable);
        }
        short rootDepthBase = resolveRootDepthBase();

        MetaCategoryDef scope = null;
        if (scopeNodeId != null) {
            scope = defRepository.findById(scopeNodeId)
                    .orElseThrow(() -> new IllegalArgumentException("scope node not found: id=" + scopeNodeId));
        }

        Page<MetaCategoryDef> page = defRepository.searchGeneric(kw, scopeNodeId, normalizeStatus(status), pageable);
        if (maxDepth != null && maxDepth > 0 && scope != null && scope.getDepth() != null) {
            short scopeDepth = scope.getDepth();
            List<MetaCategoryDef> filtered = page.getContent().stream()
                .filter(def -> def.getDepth() == null || (def.getDepth() - scopeDepth) <= maxDepth)
                .toList();
            page = new PageImpl<>(filtered, pageable, page.getTotalElements());
        }
        Map<UUID, String> titleById = loadLatestTitles(page.getContent());
        Map<UUID, Long> childCountById = loadDirectChildCounts(page.getContent());

        return page.map(def -> {
            List<MetaCategoryDef> ancestors = hierarchyRepository.findAncestorsByDescendant(def.getId());
            if (ancestors.isEmpty()) {
                ancestors = List.of(def);
            }

            Map<UUID, String> pathTitleById = loadLatestTitles(ancestors);
            List<MetaCategoryNodeDto> pathNodes = ancestors.stream()
                    .map(a -> toNodeDto(a, pathTitleById.get(a.getId()), childCountById, rootDepthBase))
                    .toList();

            MetaCategorySearchItemDto dto = new MetaCategorySearchItemDto();
                dto.setNode(toNodeDto(def, titleById.get(def.getId()), childCountById, rootDepthBase));
            dto.setPath(def.getPath());
            dto.setPathNodes(pathNodes);
            return dto;
        });
    }

    public Map<UUID, List<MetaCategoryNodeDto>> childrenBatch(MetaCategoryChildrenBatchRequestDto req) {
        requireSupportedTaxonomy(req == null ? null : req.getTaxonomy());
        if (req == null || req.getParentIds() == null || req.getParentIds().isEmpty()) {
            return Collections.emptyMap();
        }
        List<MetaCategoryDef> children = defRepository.findByParentIdInOrderBySortOrderAscCodeKeyAsc(req.getParentIds());
        String status = normalizeStatus(req.getStatus());
        if (status != null && !status.isBlank()) {
            children = children.stream()
                    .filter(d -> d.getStatus() != null && d.getStatus().equalsIgnoreCase(status))
                    .toList();
        }

        short rootDepthBase = resolveRootDepthBase();

        Map<UUID, String> titleById = loadLatestTitles(children);
        Map<UUID, Long> childCountById = loadDirectChildCounts(children);

        Map<UUID, List<MetaCategoryNodeDto>> result = new LinkedHashMap<>();
        for (UUID parentId : req.getParentIds()) {
            result.put(parentId, new ArrayList<>());
        }
        for (MetaCategoryDef child : children) {
            UUID parentId = child.getParent() == null ? null : child.getParent().getId();
            if (parentId == null)
                continue;
            result.computeIfAbsent(parentId, k -> new ArrayList<>())
                    .add(toNodeDto(child, titleById.get(child.getId()), childCountById, rootDepthBase));
        }
        return result;
    }

    public MetaTaxonomyDto taxonomy(String code) {
        requireSupportedTaxonomy(code);
        MetaTaxonomyDto dto = new MetaTaxonomyDto();
        dto.setCode(SUPPORTED_TAXONOMY);
        dto.setName(SUPPORTED_TAXONOMY);
        dto.setStatus("ACTIVE");
        dto.setLevelConfigs(List.of(
                new MetaTaxonomyLevelConfigDto(1, "Segment"),
                new MetaTaxonomyLevelConfigDto(2, "Family"),
                new MetaTaxonomyLevelConfigDto(3, "Class"),
                new MetaTaxonomyLevelConfigDto(4, "Commodity")
        ));
        return dto;
    }

    private MetaCategoryNodeDto toNodeDto(MetaCategoryDef def,
                                          String latestTitle,
                                          Map<UUID, Long> childCountById,
                                          short rootDepthBase) {
        MetaCategoryNodeDto dto = new MetaCategoryNodeDto();
        dto.setId(def.getId());
        dto.setTaxonomy(SUPPORTED_TAXONOMY);
        dto.setCode(def.getCodeKey());
        dto.setName((latestTitle != null && !latestTitle.isBlank()) ? latestTitle : def.getCodeKey());
        dto.setLevel(depthToLevel(def.getDepth(), rootDepthBase));
        dto.setParentId(def.getParent() == null ? null : def.getParent().getId());
        dto.setPath(def.getPath());

        long directChildren = childCountById.getOrDefault(def.getId(), 0L);
        boolean hasChildren = directChildren > 0 || Boolean.FALSE.equals(def.getIsLeaf());
        dto.setHasChildren(hasChildren);
        dto.setLeaf(!hasChildren);

        dto.setStatus(def.getStatus() == null ? null : def.getStatus().toUpperCase(Locale.ROOT));
        dto.setSort(def.getSortOrder());
        dto.setCreatedAt(def.getCreatedAt());
        dto.setUpdatedAt(null);
        return dto;
    }

    private Map<UUID, String> loadLatestTitles(Collection<MetaCategoryDef> defs) {
        if (defs == null || defs.isEmpty()) {
            return Collections.emptyMap();
        }
        List<MetaCategoryVersion> versions = versionRepository.findByCategoryDefInAndIsLatestTrue(defs);
        Map<UUID, String> map = new HashMap<>(versions.size() * 2);
        for (MetaCategoryVersion version : versions) {
            if (version.getCategoryDef() == null) {
                continue;
            }
            map.put(version.getCategoryDef().getId(), version.getDisplayName());
        }
        return map;
    }

    private Map<UUID, Long> loadDirectChildCounts(Collection<MetaCategoryDef> defs) {
        if (defs == null || defs.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UUID> ids = defs.stream().map(MetaCategoryDef::getId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Object[]> rows = hierarchyRepository.countDirectChildrenByAncestorIds(ids);
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof UUID)) {
                continue;
            }
            long count = row[1] instanceof Number ? ((Number) row[1]).longValue() : 0L;
            map.put((UUID) row[0], count);
        }
        return map;
    }

    private void requireSupportedTaxonomy(String taxonomy) {
        if (taxonomy == null || taxonomy.isBlank()) {
            throw new IllegalArgumentException("taxonomy is required");
        }
        if (!SUPPORTED_TAXONOMY.equalsIgnoreCase(taxonomy.trim())) {
            throw new IllegalArgumentException("taxonomy not supported: " + taxonomy);
        }
    }

    private Short toDepth(Integer level, short rootDepthBase) {
        if (level == null) {
            return null;
        }
        if (level <= 0) {
            throw new IllegalArgumentException("level must be greater than 0");
        }
        return (short) (rootDepthBase + level - 1);
    }

    private Integer depthToLevel(Short depth, short rootDepthBase) {
        if (depth == null) {
            return null;
        }
        int level = depth - rootDepthBase + 1;
        return Math.max(level, 1);
    }

    private short resolveRootDepthBase() {
        Short minDepth = defRepository.findMinRootDepth();
        return minDepth == null ? 0 : minDepth;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeStatus(String status) {
        String normalized = trimToNull(status);
        return normalized == null ? "ACTIVE" : normalized;
    }
}
