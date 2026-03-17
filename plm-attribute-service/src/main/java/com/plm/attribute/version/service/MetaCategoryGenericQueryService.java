package com.plm.attribute.version.service;

import com.plm.common.api.dto.*;
import com.plm.common.version.domain.CategoryHierarchy;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MetaCategoryGenericQueryService {

    private static final int SUBTREE_DEFAULT_NODE_LIMIT = 2000;
    private static final int SUBTREE_MAX_NODE_LIMIT = 10000;

    private enum BusinessDomain {
        PRODUCT,
        MATERIAL,
        BOM,
        PROCESS,
        TEST,
        EXPERIMENT
    }

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

    public Page<MetaCategoryNodeDto> listNodes(String businessDomain,
                                               UUID parentId,
                                               Integer level,
                                               String keyword,
                                               String status,
                                               Pageable pageable) {
        String normalizedBusinessDomain = normalizeBusinessDomain(businessDomain);
        short rootDepthBase = resolveRootDepthBase();
        Short depth = level == null ? null : toDepth(level, rootDepthBase);
        Page<MetaCategoryDef> page = defRepository.findNodePage(
                normalizedBusinessDomain,
                parentId,
                depth,
                normalizeStatus(status),
                trimToNull(keyword),
                pageable);

        Map<UUID, String> titleById = loadLatestTitles(page.getContent());
        Map<UUID, Long> childCountById = loadDirectChildCounts(page.getContent());
        return page.map(def -> toNodeDto(def, titleById.get(def.getId()), childCountById, rootDepthBase));
    }

    public List<MetaCategoryNodeDto> path(UUID id, String businessDomain) {
        String normalizedBusinessDomain = normalizeBusinessDomain(businessDomain);
        MetaCategoryDef target = defRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("category not found: id=" + id));
        if (!normalizedBusinessDomain.equalsIgnoreCase(target.getBusinessDomain())) {
            throw new IllegalArgumentException("category not found in businessDomain: id=" + id + ", businessDomain=" + normalizedBusinessDomain);
        }

        List<MetaCategoryDef> defs = hierarchyRepository.findAncestorsByDescendant(target.getId());
        if (defs.isEmpty()) {
            defs = List.of(target);
        }
        short rootDepthBase = resolveRootDepthBase();
        Map<UUID, String> titleById = loadLatestTitles(defs);
        Map<UUID, Long> childCountById = loadDirectChildCounts(defs);
        return defs.stream().map(d -> toNodeDto(d, titleById.get(d.getId()), childCountById, rootDepthBase)).toList();
    }

    public Page<MetaCategorySearchItemDto> search(String businessDomain,
                                                  String keyword,
                                                  UUID scopeNodeId,
                                                  Integer maxDepth,
                                                  String status,
                                                  Pageable pageable) {
        String normalizedBusinessDomain = normalizeBusinessDomain(businessDomain);
        String kw = trimToNull(keyword);
        if (kw == null) {
            return Page.empty(pageable);
        }
        short rootDepthBase = resolveRootDepthBase();

        MetaCategoryDef scope = null;
        if (scopeNodeId != null) {
            scope = defRepository.findById(scopeNodeId)
                    .orElseThrow(() -> new IllegalArgumentException("scope node not found: id=" + scopeNodeId));
            if (!normalizedBusinessDomain.equalsIgnoreCase(scope.getBusinessDomain())) {
                throw new IllegalArgumentException("scope node not found in businessDomain: id=" + scopeNodeId + ", businessDomain=" + normalizedBusinessDomain);
            }
        }

        Page<MetaCategoryDef> page = defRepository.searchGeneric(normalizedBusinessDomain, kw, scopeNodeId, normalizeStatus(status), pageable);
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
        String normalizedBusinessDomain = normalizeBusinessDomain(req == null ? null : req.getBusinessDomain());
        if (req == null || req.getParentIds() == null || req.getParentIds().isEmpty()) {
            return Collections.emptyMap();
        }
        List<MetaCategoryDef> children = defRepository.findByParentIdInOrderBySortOrderAscCodeKeyAsc(req.getParentIds());
        children = children.stream()
                .filter(d -> normalizedBusinessDomain.equalsIgnoreCase(d.getBusinessDomain()))
                .toList();
        String status = normalizeStatus(req.getStatus());
        if ("ALL".equalsIgnoreCase(status)) {
            children = children.stream()
                .filter(d -> d.getStatus() == null || !"deleted".equalsIgnoreCase(d.getStatus()))
                .toList();
        } else if (status != null && !status.isBlank()) {
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

    public MetaCategorySubtreeResponseDto subtree(MetaCategorySubtreeRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.getParentId() == null) {
            throw new IllegalArgumentException("parentId is required");
        }

        MetaCategoryDef root = defRepository.findById(request.getParentId())
                .orElseThrow(() -> new IllegalArgumentException("category not found: id=" + request.getParentId()));

        boolean includeRoot = request.getIncludeRoot() == null || request.getIncludeRoot();
        int maxDepth = normalizeSubtreeMaxDepth(request.getMaxDepth());
        int nodeLimit = normalizeSubtreeNodeLimit(request.getNodeLimit());
        String mode = normalizeSubtreeMode(request.getMode());
        String status = normalizeSubtreeStatus(request.getStatus());
        int dbReadLimit = Math.min(nodeLimit + 1, SUBTREE_MAX_NODE_LIMIT);
        Pageable limitPageable = PageRequest.of(0, dbReadLimit);

        List<CategoryHierarchy> closureRows = hierarchyRepository.findSubtreeByAncestor(
                root.getId(),
                includeRoot,
                maxDepth,
                status,
                limitPageable);
        List<SubtreeNodeHolder> holders = new ArrayList<>();
        for (CategoryHierarchy row : closureRows) {
            if (row == null || row.getDescendantDef() == null || row.getDistance() == null) {
                continue;
            }
            MetaCategoryDef def = row.getDescendantDef();
            holders.add(new SubtreeNodeHolder(def, row.getDistance().intValue()));
        }

        boolean truncated = holders.size() > nodeLimit;
        if (truncated) {
            holders = new ArrayList<>(holders.subList(0, nodeLimit));
        }

        List<MetaCategoryDef> defs = holders.stream().map(SubtreeNodeHolder::def).toList();
        Map<UUID, String> titleById = loadLatestTitles(defs);
        Map<UUID, Long> childCountById = loadDirectChildCounts(defs);
        short rootDepthBase = resolveRootDepthBase();

        Object data;
        int depthReached = 0;

        if ("TREE".equals(mode)) {
            TreeBuildResult treeBuildResult = buildTreeData(holders, titleById, childCountById, rootDepthBase, includeRoot, root.getId());
            data = treeBuildResult.data();
            depthReached = treeBuildResult.depthReached();
        } else {
            List<MetaCategorySubtreeFlatNodeDto> flatData = new ArrayList<>();
            for (SubtreeNodeHolder holder : holders) {
                MetaCategorySubtreeFlatNodeDto item = toSubtreeFlatNodeDto(
                        holder.def(),
                        titleById.get(holder.def().getId()),
                        childCountById,
                        rootDepthBase,
                        holder.relativeDepth());
                flatData.add(item);
                depthReached = Math.max(depthReached, holder.relativeDepth());
            }
            data = flatData;
        }

        MetaCategorySubtreeResponseDto response = new MetaCategorySubtreeResponseDto();
        response.setParentId(root.getId());
        response.setMode(mode);
        response.setTotalNodes(holders.size());
        response.setTruncated(truncated);
        response.setDepthReached(depthReached);
        if (truncated) {
            response.setMessage("subtree result truncated by nodeLimit=" + nodeLimit);
        }
        response.setData(data);
        return response;
    }

    private MetaCategoryNodeDto toNodeDto(MetaCategoryDef def,
                                          String latestTitle,
                                          Map<UUID, Long> childCountById,
                                          short rootDepthBase) {
        MetaCategoryNodeDto dto = new MetaCategoryNodeDto();
        dto.setId(def.getId());
        dto.setBusinessDomain(def.getBusinessDomain());
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

    private String normalizeBusinessDomain(String businessDomain) {
        if (businessDomain == null || businessDomain.isBlank()) {
            throw new IllegalArgumentException("businessDomain is required");
        }
        String normalized = businessDomain.trim().toUpperCase(Locale.ROOT);
        try {
            return BusinessDomain.valueOf(normalized).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("businessDomain not supported: " + businessDomain);
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
        return normalized == null ? "ALL" : normalized;
    }

    private int normalizeSubtreeNodeLimit(Integer nodeLimit) {
        int resolved = nodeLimit == null ? SUBTREE_DEFAULT_NODE_LIMIT : nodeLimit;
        if (resolved <= 0) {
            throw new IllegalArgumentException("nodeLimit must be greater than 0");
        }
        if (resolved > SUBTREE_MAX_NODE_LIMIT) {
            throw new IllegalArgumentException("nodeLimit must be <= " + SUBTREE_MAX_NODE_LIMIT);
        }
        return resolved;
    }

    private int normalizeSubtreeMaxDepth(Integer maxDepth) {
        if (maxDepth == null) {
            return -1;
        }
        if (maxDepth < -1) {
            throw new IllegalArgumentException("maxDepth must be >= -1");
        }
        return maxDepth;
    }

    private String normalizeSubtreeMode(String mode) {
        String normalized = trimToNull(mode);
        if (normalized == null) {
            return "FLAT";
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!"FLAT".equals(upper) && !"TREE".equals(upper)) {
            throw new IllegalArgumentException("mode not supported: " + mode);
        }
        return upper;
    }

    private String normalizeSubtreeStatus(String status) {
        String normalized = trimToNull(status);
        String resolved = normalized == null ? "ACTIVE" : normalized.toUpperCase(Locale.ROOT);
        if (!"ALL".equals(resolved)
                && !"ACTIVE".equals(resolved)
                && !"INACTIVE".equals(resolved)
                && !"DRAFT".equals(resolved)) {
            throw new IllegalArgumentException("status not supported: " + status);
        }
        return switch (resolved) {
            case "ACTIVE" -> "active";
            case "INACTIVE" -> "inactive";
            case "DRAFT" -> "draft";
            case "ALL" -> "ALL";
            default -> throw new IllegalArgumentException("status not supported: " + status);
        };
    }

    private MetaCategorySubtreeFlatNodeDto toSubtreeFlatNodeDto(MetaCategoryDef def,
                                                                 String latestTitle,
                                                                 Map<UUID, Long> childCountById,
                                                                 short rootDepthBase,
                                                                 int relativeDepth) {
        MetaCategorySubtreeFlatNodeDto dto = new MetaCategorySubtreeFlatNodeDto();
        dto.setId(def.getId());
        dto.setParentId(def.getParent() == null ? null : def.getParent().getId());
        dto.setBusinessDomain(def.getBusinessDomain());
        dto.setCode(def.getCodeKey());
        dto.setName((latestTitle != null && !latestTitle.isBlank()) ? latestTitle : def.getCodeKey());
        dto.setStatus(def.getStatus() == null ? null : def.getStatus().toUpperCase(Locale.ROOT));
        dto.setLevel(depthToLevel(def.getDepth(), rootDepthBase));
        dto.setDepth(relativeDepth);
        dto.setPath(def.getPath());
        long directChildren = childCountById.getOrDefault(def.getId(), 0L);
        boolean hasChildren = directChildren > 0 || Boolean.FALSE.equals(def.getIsLeaf());
        dto.setHasChildren(hasChildren);
        dto.setLeaf(!hasChildren);
        dto.setSort(def.getSortOrder());
        return dto;
    }

    private MetaCategorySubtreeTreeNodeDto toSubtreeTreeNodeDto(MetaCategorySubtreeFlatNodeDto flatNode) {
        MetaCategorySubtreeTreeNodeDto node = new MetaCategorySubtreeTreeNodeDto();
        node.setId(flatNode.getId());
        node.setParentId(flatNode.getParentId());
        node.setBusinessDomain(flatNode.getBusinessDomain());
        node.setCode(flatNode.getCode());
        node.setName(flatNode.getName());
        node.setStatus(flatNode.getStatus());
        node.setLevel(flatNode.getLevel());
        node.setDepth(flatNode.getDepth());
        node.setPath(flatNode.getPath());
        node.setHasChildren(flatNode.getHasChildren());
        node.setLeaf(flatNode.getLeaf());
        node.setSort(flatNode.getSort());
        return node;
    }

    private TreeBuildResult buildTreeData(List<SubtreeNodeHolder> holders,
                                          Map<UUID, String> titleById,
                                          Map<UUID, Long> childCountById,
                                          short rootDepthBase,
                                          boolean includeRoot,
                                          UUID rootId) {
        Map<UUID, MetaCategorySubtreeTreeNodeDto> nodeById = new LinkedHashMap<>();
        int depthReached = 0;

        for (SubtreeNodeHolder holder : holders) {
            MetaCategorySubtreeFlatNodeDto flatNode = toSubtreeFlatNodeDto(
                    holder.def(),
                    titleById.get(holder.def().getId()),
                    childCountById,
                    rootDepthBase,
                    holder.relativeDepth());
            nodeById.put(flatNode.getId(), toSubtreeTreeNodeDto(flatNode));
            depthReached = Math.max(depthReached, holder.relativeDepth());
        }

        List<MetaCategorySubtreeTreeNodeDto> roots = new ArrayList<>();
        for (MetaCategorySubtreeTreeNodeDto node : nodeById.values()) {
            UUID parentId = node.getParentId();
            if (parentId != null && nodeById.containsKey(parentId)) {
                nodeById.get(parentId).getChildren().add(node);
                continue;
            }
            roots.add(node);
        }

        Object data;
        if (includeRoot && nodeById.containsKey(rootId)) {
            data = nodeById.get(rootId);
        } else {
            data = roots;
        }
        return new TreeBuildResult(data, depthReached);
    }

    private record SubtreeNodeHolder(MetaCategoryDef def, int relativeDepth) {
    }

    private record TreeBuildResult(Object data, int depthReached) {
    }
}
