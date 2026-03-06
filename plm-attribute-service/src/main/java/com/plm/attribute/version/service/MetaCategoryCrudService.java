package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.attribute.version.exception.CategoryConflictException;
import com.plm.attribute.version.exception.CategoryNotFoundException;
import com.plm.common.api.dto.CreateCategoryRequestDto;
import com.plm.common.api.dto.MetaCategoryDetailDto;
import com.plm.common.api.dto.MetaCategoryLatestVersionDto;
import com.plm.common.api.dto.UpdateCategoryRequestDto;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MetaCategoryCrudService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";
    private static final String STATUS_DELETED = "deleted";

    private final MetaCategoryDefRepository defRepository;
    private final MetaCategoryVersionRepository versionRepository;
    private final CategoryHierarchyRepository hierarchyRepository;
    private final MetaCategoryHierarchyService hierarchyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private enum BusinessDomain {
        PRODUCT,
        MATERIAL,
        BOM,
        PROCESS,
        TEST,
        EXPERIMENT
    }

    public MetaCategoryCrudService(MetaCategoryDefRepository defRepository,
                                   MetaCategoryVersionRepository versionRepository,
                                   CategoryHierarchyRepository hierarchyRepository,
                                   MetaCategoryHierarchyService hierarchyService) {
        this.defRepository = defRepository;
        this.versionRepository = versionRepository;
        this.hierarchyRepository = hierarchyRepository;
        this.hierarchyService = hierarchyService;
    }

    @Transactional
    public MetaCategoryDetailDto create(CreateCategoryRequestDto req, String operator) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String code = requireCode(req.getCode());
        String name = requireName(req.getName());
        String businessDomain = normalizeBusinessDomain(req.getBusinessDomain());
        String status = mapApiStatusToDb(req.getStatus(), true);

        if (defRepository.existsByBusinessDomainAndCodeKey(businessDomain, code)) {
            throw new IllegalArgumentException("category already exists: businessDomain=" + businessDomain + ", code=" + code);
        }

        MetaCategoryDef parent = null;
        if (req.getParentId() != null) {
            parent = loadExisting(req.getParentId());
            ensureParentActive(parent);
        }

        MetaCategoryDef def = new MetaCategoryDef();
        def.setCodeKey(code);
        def.setBusinessDomain(businessDomain);
        def.setParent(parent);
        def.setStatus(status);
        def.setSortOrder(req.getSort() == null ? 0 : req.getSort());
        def.setIsLeaf(Boolean.TRUE);
        def.setCreatedBy(normalizeOperator(operator));
        defRepository.save(def);

        MetaCategoryVersion version = new MetaCategoryVersion();
        version.setCategoryDef(def);
        version.setVersionNo(1);
        version.setDisplayName(name);
        version.setStructureJson(buildStructureJson(req.getDescription()));
        version.setIsLatest(true);
        version.setCreatedBy(normalizeOperator(operator));
        versionRepository.save(version);

        if (parent != null && Boolean.TRUE.equals(parent.getIsLeaf())) {
            parent.setIsLeaf(Boolean.FALSE);
            defRepository.save(parent);
        }

        rebuildTreeAndClosure();
        return detail(def.getId());
    }

    @Transactional
    public MetaCategoryDetailDto update(UUID id, UpdateCategoryRequestDto req, String operator, boolean patchMode) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        MetaCategoryDef def = loadExisting(id);
        if (isDeleted(def)) {
            throw new CategoryNotFoundException("category is deleted: id=" + id);
        }

        if (!isBlank(req.getCode()) && !def.getCodeKey().equals(req.getCode().trim())) {
            throw new IllegalArgumentException("code cannot be modified");
        }

        String normalizedOperator = normalizeOperator(operator);
        boolean defChanged = false;

        if (!isBlank(req.getBusinessDomain())) {
            String nextDomain = normalizeBusinessDomain(req.getBusinessDomain());
            if (!Objects.equals(nextDomain, def.getBusinessDomain())) {
                throw new IllegalArgumentException("businessDomain cannot be modified after creation");
            }
        }

        if (req.getParentId() != null || !patchMode) {
            MetaCategoryDef nextParent = null;
            if (req.getParentId() != null) {
                nextParent = loadExisting(req.getParentId());
                ensureParentActive(nextParent);
                if (nextParent.getId().equals(def.getId())) {
                    throw new IllegalArgumentException("parentId cannot be self");
                }
                ensureNoCycle(def.getId(), nextParent.getId());
            }

            UUID oldParentId = def.getParent() == null ? null : def.getParent().getId();
            UUID nextParentId = nextParent == null ? null : nextParent.getId();
            if (!Objects.equals(oldParentId, nextParentId)) {
                def.setParent(nextParent);
                defChanged = true;
            }
        }

        if (req.getSort() != null && !Objects.equals(def.getSortOrder(), req.getSort())) {
            def.setSortOrder(req.getSort());
            defChanged = true;
        }

        if (!isBlank(req.getStatus()) || !patchMode) {
            String nextStatus = mapApiStatusToDb(req.getStatus(), false);
            if (!Objects.equals(nextStatus, def.getStatus())) {
                def.setStatus(nextStatus);
                defChanged = true;
            }
        }

        MetaCategoryVersion latest = versionRepository.findLatestByDef(def)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: id=" + id));

        String nextName = patchMode ? coalesceTrim(req.getName(), latest.getDisplayName()) : requireName(req.getName());
        String nextDescription = patchMode ? coalesceTrim(req.getDescription(), readDescription(latest.getStructureJson())) : trimToNull(req.getDescription());

        boolean versionChanged = !Objects.equals(nextName, latest.getDisplayName())
                || !Objects.equals(nextDescription, readDescription(latest.getStructureJson()));

        if (versionChanged) {
            latest.setIsLatest(false);
            versionRepository.save(latest);

            MetaCategoryVersion nextVersion = new MetaCategoryVersion();
            nextVersion.setCategoryDef(def);
            nextVersion.setVersionNo(latest.getVersionNo() + 1);
            nextVersion.setDisplayName(nextName);
            nextVersion.setStructureJson(buildStructureJson(nextDescription));
            nextVersion.setIsLatest(true);
            nextVersion.setCreatedBy(normalizedOperator);
            versionRepository.save(nextVersion);
        }

        if (defChanged) {
            defRepository.save(def);
            rebuildTreeAndClosure();
        }

        return detail(def.getId());
    }

    @Transactional
    public int delete(UUID id, boolean cascade, boolean confirm, String operator) {
        MetaCategoryDef def = loadExisting(id);
        if (isDeleted(def)) {
            throw new CategoryNotFoundException("category is already deleted: id=" + id);
        }

        long directChildren = hierarchyRepository.countDirectChildren(id);
        if (directChildren > 0 && (!cascade || !confirm)) {
            throw new CategoryConflictException(
                    "CATEGORY_HAS_CHILDREN",
                    "category has children, please confirm cascade deletion with cascade=true&confirm=true"
            );
        }

        List<MetaCategoryDef> targets;
        if (cascade) {
            List<UUID> descendantIds = hierarchyRepository.findDescendantIdsIncludingSelf(id);
            if (descendantIds.isEmpty()) {
                descendantIds = List.of(id);
            }
            targets = defRepository.findAllById(descendantIds);
        } else {
            targets = List.of(def);
        }

        int changed = 0;
        for (MetaCategoryDef target : targets) {
            if (!isDeleted(target)) {
                target.setStatus(STATUS_DELETED);
                changed++;
            }
        }
        defRepository.saveAll(targets);

        // 软删除后重新计算叶子节点标记，避免父节点状态变化后叶子状态不一致。
        rebuildTreeAndClosure();
        return changed;
    }

    @Transactional(readOnly = true)
    public MetaCategoryDetailDto detail(UUID id) {
        MetaCategoryDef def = loadExisting(id);
        MetaCategoryVersion latest = versionRepository.findLatestByDef(def)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: id=" + id));

        List<MetaCategoryDef> ancestors = hierarchyRepository.findAncestorsByDescendant(id);
        MetaCategoryDef root = ancestors.isEmpty() ? def : ancestors.get(0);

        MetaCategoryDetailDto dto = new MetaCategoryDetailDto();
        dto.setId(def.getId());
        dto.setCode(def.getCodeKey());
        dto.setBusinessDomain(def.getBusinessDomain());
        dto.setStatus(mapDbStatusToApi(def.getStatus()));
        dto.setParentId(def.getParent() == null ? null : def.getParent().getId());
        dto.setRootId(root == null ? def.getId() : root.getId());
        dto.setRootCode(root == null ? def.getCodeKey() : root.getCodeKey());
        dto.setPath(def.getPath());
        dto.setDepth(def.getDepth());
        dto.setLevel(depthToLevel(def.getDepth(), resolveRootDepthBase()));
        dto.setSort(def.getSortOrder());
        dto.setCreatedBy(def.getCreatedBy());
        dto.setCreatedAt(def.getCreatedAt());

        MetaCategoryLatestVersionDto latestDto = new MetaCategoryLatestVersionDto();
        latestDto.setVersionNo(latest.getVersionNo());
        latestDto.setVersionDate(latest.getCreatedAt());
        latestDto.setName(latest.getDisplayName());
        latestDto.setDescription(readDescription(latest.getStructureJson()));
        latestDto.setUpdatedBy(latest.getCreatedBy());
        dto.setLatestVersion(latestDto);
        return dto;
    }

    private void rebuildTreeAndClosure() {
        recalculateTreeMeta();
        hierarchyService.rebuildClosure();
    }

    private void recalculateTreeMeta() {
        List<MetaCategoryDef> all = defRepository.findAll();
        if (all.isEmpty()) {
            return;
        }

        Map<UUID, MetaCategoryDef> byId = all.stream().collect(Collectors.toMap(MetaCategoryDef::getId, d -> d));
        Map<UUID, List<MetaCategoryDef>> childrenByParent = new HashMap<>();
        for (MetaCategoryDef def : all) {
            UUID parentId = def.getParent() == null ? null : def.getParent().getId();
            if (parentId != null && byId.containsKey(parentId)) {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(def);
            }
        }

        List<MetaCategoryDef> roots = all.stream()
                .filter(d -> d.getParent() == null || !byId.containsKey(d.getParent().getId()))
                .sorted(Comparator.comparing(MetaCategoryDef::getCodeKey))
                .toList();

        Queue<MetaCategoryDef> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            MetaCategoryDef current = queue.poll();
            MetaCategoryDef parent = current.getParent();
            String currentName = latestTitleOrCode(current);

            if (parent == null || !byId.containsKey(parent.getId())) {
                current.setPath("/" + current.getCodeKey());
                current.setDepth((short) 0);
                current.setFullPathName(currentName);
            } else {
                String parentPath = parent.getPath() == null ? "/" + parent.getCodeKey() : parent.getPath();
                current.setPath(parentPath + "/" + current.getCodeKey());
                short parentDepth = parent.getDepth() == null ? 0 : parent.getDepth();
                current.setDepth((short) (parentDepth + 1));
                String parentFullPath = parent.getFullPathName() == null ? latestTitleOrCode(parent) : parent.getFullPathName();
                current.setFullPathName(parentFullPath + "/" + currentName);
            }

            List<MetaCategoryDef> children = childrenByParent.getOrDefault(current.getId(), List.of());
            current.setIsLeaf(children.isEmpty());

            List<MetaCategoryDef> sortedChildren = children.stream()
                    .sorted(Comparator.comparing(MetaCategoryDef::getSortOrder)
                            .thenComparing(MetaCategoryDef::getCodeKey))
                    .toList();
            queue.addAll(sortedChildren);
        }

        defRepository.saveAll(all);
    }

    private String latestTitleOrCode(MetaCategoryDef def) {
        return versionRepository.findLatestByDef(def)
                .map(MetaCategoryVersion::getDisplayName)
                .filter(v -> !v.isBlank())
                .orElse(def.getCodeKey());
    }

    private void ensureNoCycle(UUID currentId, UUID nextParentId) {
        List<UUID> descendants = hierarchyRepository.findDescendantIdsIncludingSelf(currentId);
        if (descendants.contains(nextParentId)) {
            throw new IllegalArgumentException("parentId cannot be a descendant of current node");
        }
    }

    private MetaCategoryDef loadExisting(UUID id) {
        return defRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException("category not found: id=" + id));
    }

    private void ensureParentActive(MetaCategoryDef parent) {
        if (parent == null || parent.getStatus() == null) {
            return;
        }
        if (STATUS_DELETED.equalsIgnoreCase(parent.getStatus().trim())) {
            throw new IllegalArgumentException("parent category is deleted: id=" + parent.getId());
        }
    }

    private boolean isDeleted(MetaCategoryDef def) {
        return def != null
                && def.getStatus() != null
                && STATUS_DELETED.equalsIgnoreCase(def.getStatus().trim());
    }

    private String mapApiStatusToDb(String status, boolean createMode) {
        String normalized = trimToNull(status);
        if (normalized == null) {
            return createMode ? STATUS_DRAFT : STATUS_ACTIVE;
        }
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "CREATED" -> STATUS_DRAFT;
            case "EFFECTIVE" -> STATUS_ACTIVE;
            case "INVALID" -> STATUS_INACTIVE;
            case "DRAFT" -> STATUS_DRAFT;
            case "ACTIVE" -> STATUS_ACTIVE;
            case "INACTIVE" -> STATUS_INACTIVE;
            default -> throw new IllegalArgumentException("unsupported status: " + status);
        };
    }

    private String mapDbStatusToApi(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case STATUS_DRAFT -> "CREATED";
            case STATUS_ACTIVE -> "EFFECTIVE";
            case STATUS_INACTIVE -> "INVALID";
            case STATUS_DELETED -> "DELETED";
            default -> status.toUpperCase(Locale.ROOT);
        };
    }

    private String normalizeBusinessDomain(String businessDomain) {
        String normalized = trimToNull(businessDomain);
        if (normalized == null) {
            throw new IllegalArgumentException("businessDomain is required");
        }
        try {
            return BusinessDomain.valueOf(normalized.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unsupported businessDomain: " + businessDomain);
        }
    }

    private String requireCode(String code) {
        String normalized = trimToNull(code);
        if (normalized == null) {
            throw new IllegalArgumentException("code is required");
        }
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("code length must be <= 64");
        }
        return normalized;
    }

    private String requireName(String name) {
        String normalized = trimToNull(name);
        if (normalized == null) {
            throw new IllegalArgumentException("name is required");
        }
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("name length must be <= 255");
        }
        return normalized;
    }

    private String buildStructureJson(String description) {
        ObjectNode root = objectMapper.createObjectNode();
        String normalizedDescription = trimToNull(description);
        if (normalizedDescription != null) {
            root.put("description", normalizedDescription);
        }
        return root.toString();
    }

    private String readDescription(String structureJson) {
        String normalizedJson = trimToNull(structureJson);
        if (normalizedJson == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(normalizedJson);
            JsonNode description = node.get("description");
            if (description == null || description.isNull()) {
                return null;
            }
            return trimToNull(description.asText());
        } catch (Exception ex) {
            return null;
        }
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

    private String coalesceTrim(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String normalizeOperator(String operator) {
        String normalized = trimToNull(operator);
        return normalized == null ? "system" : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }
}
