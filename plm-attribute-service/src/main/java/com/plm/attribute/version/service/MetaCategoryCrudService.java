package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.attribute.version.exception.CategoryConflictException;
import com.plm.attribute.version.exception.CategoryNotFoundException;
import com.plm.common.api.dto.CreateCategoryRequestDto;
import com.plm.common.api.dto.MetaCategoryDetailDto;
import com.plm.common.api.dto.MetaCategoryLatestVersionDto;
import com.plm.common.api.dto.MetaCategoryVersionCompareDiffDto;
import com.plm.common.api.dto.MetaCategoryVersionCompareDto;
import com.plm.common.api.dto.MetaCategoryVersionHistoryDto;
import com.plm.common.api.dto.MetaCategoryVersionSnapshotDto;
import com.plm.common.api.dto.UpdateCategoryRequestDto;
import com.plm.common.version.domain.CategoryHierarchy;
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
import java.util.Set;
import java.util.TreeSet;
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
        Integer targetSort = req.getSort() == null ? nextSort(parent == null ? null : parent.getId()) : req.getSort();
        def.setSortOrder(targetSort);
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

        // 创建场景使用增量维护，避免触发整树重建带来的高频查询。
        applyCreateNodeMeta(def, parent, name);

        if (parent != null && Boolean.TRUE.equals(parent.getIsLeaf())) {
            parent.setIsLeaf(Boolean.FALSE);
            defRepository.save(parent);
        }

        normalizeSiblingOrders(parent == null ? null : parent.getId());

        insertClosureForNewNode(def, parent);
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
        boolean parentChanged = false;
        boolean sortChanged = false;
        MetaCategoryDef oldParent = def.getParent();

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
                parentChanged = true;
            }
        }

        if (req.getSort() != null && !Objects.equals(def.getSortOrder(), req.getSort())) {
            def.setSortOrder(req.getSort());
            defChanged = true;
            sortChanged = true;
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
            if (parentChanged) {
                if (req.getSort() == null) {
                    def.setSortOrder(nextSort(def.getParent() == null ? null : def.getParent().getId()));
                    defRepository.save(def);
                }
                applyParentMove(def, oldParent);
                normalizeSiblingOrders(oldParent == null ? null : oldParent.getId());
                normalizeSiblingOrders(def.getParent() == null ? null : def.getParent().getId());
            } else if (sortChanged) {
                normalizeSiblingOrders(def.getParent() == null ? null : def.getParent().getId());
            }
        }

        return detail(def.getId());
    }

    @Transactional
    public int delete(UUID id, boolean cascade, boolean confirm, String operator) {
        MetaCategoryDef def = loadExisting(id);
        MetaCategoryDef oldParent = def.getParent();
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

        refreshParentLeafIfNeeded(oldParent);
        normalizeSiblingOrders(oldParent == null ? null : oldParent.getId());
        return changed;
    }

    @Transactional(readOnly = true)
    public MetaCategoryDetailDto detail(UUID id) {
        MetaCategoryDef def = loadExisting(id);
        MetaCategoryVersion latest = versionRepository.findLatestByDef(def)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: id=" + id));
        List<MetaCategoryVersion> versions = versionRepository.findByCategoryDefOrderByVersionNoAsc(def);

        List<MetaCategoryDef> ancestors = hierarchyRepository.findAncestorsByDescendant(id);
        MetaCategoryDef root = ancestors.isEmpty() ? def : ancestors.get(0);
        MetaCategoryDef parent = def.getParent();

        String parentName = null;
        if (parent != null) {
            parentName = versionRepository.findLatestByDef(parent)
                .map(MetaCategoryVersion::getDisplayName)
                .orElse(parent.getCodeKey());
        }
        String rootName = versionRepository.findLatestByDef(root)
            .map(MetaCategoryVersion::getDisplayName)
            .orElse(root.getCodeKey());

        MetaCategoryDetailDto dto = new MetaCategoryDetailDto();
        dto.setId(def.getId());
        dto.setCode(def.getCodeKey());
        dto.setBusinessDomain(def.getBusinessDomain());
        dto.setStatus(mapDbStatusToApi(def.getStatus()));
        dto.setParentId(parent == null ? null : parent.getId());
        dto.setParentCode(parent == null ? null : parent.getCodeKey());
        dto.setParentName(parentName);
        dto.setRootId(root == null ? def.getId() : root.getId());
        dto.setRootCode(root == null ? def.getCodeKey() : root.getCodeKey());
        dto.setRootName(rootName);
        dto.setPath(def.getPath());
        dto.setDepth(def.getDepth());
        dto.setLevel(depthToLevel(def.getDepth(), resolveRootDepthBase()));
        dto.setSort(def.getSortOrder());
        dto.setDescription(readDescription(latest.getStructureJson()));
        dto.setCreatedBy(def.getCreatedBy());
        dto.setCreatedAt(def.getCreatedAt());
        dto.setModifiedBy(latest.getCreatedBy());
        dto.setModifiedAt(latest.getCreatedAt());
        dto.setVersion(latest.getVersionNo());

        MetaCategoryLatestVersionDto latestDto = new MetaCategoryLatestVersionDto();
        latestDto.setVersionNo(latest.getVersionNo());
        latestDto.setVersionDate(latest.getCreatedAt());
        latestDto.setName(latest.getDisplayName());
        latestDto.setDescription(readDescription(latest.getStructureJson()));
        latestDto.setUpdatedBy(latest.getCreatedBy());
        dto.setLatestVersion(latestDto);

        List<MetaCategoryVersionHistoryDto> history = versions.stream()
                .sorted(Comparator.comparing(MetaCategoryVersion::getVersionNo).reversed())
                .map(v -> {
                    MetaCategoryVersionHistoryDto h = new MetaCategoryVersionHistoryDto();
                    h.setVersionId(v.getId());
                    h.setVersionNo(v.getVersionNo());
                    h.setVersionDate(v.getCreatedAt());
                    h.setName(v.getDisplayName());
                    h.setDescription(readDescription(v.getStructureJson()));
                    h.setUpdatedBy(v.getCreatedBy());
                    h.setLatest(Boolean.TRUE.equals(v.getIsLatest()));
                    return h;
                })
                .toList();
        dto.setHistoryVersions(history);

        return dto;
    }

    @Transactional(readOnly = true)
    public MetaCategoryVersionCompareDto compareVersions(UUID categoryId, UUID baseVersionId, UUID targetVersionId) {
        if (baseVersionId == null) {
            throw new IllegalArgumentException("baseVersionId is required");
        }
        if (targetVersionId == null) {
            throw new IllegalArgumentException("targetVersionId is required");
        }

        MetaCategoryDef def = loadExisting(categoryId);
        MetaCategoryVersion baseVersion = loadVersion(baseVersionId);
        MetaCategoryVersion targetVersion = loadVersion(targetVersionId);

        ensureVersionBelongsToCategory(def, baseVersion, "baseVersionId");
        ensureVersionBelongsToCategory(def, targetVersion, "targetVersionId");

        MetaCategoryVersionSnapshotDto baseSnapshot = toVersionSnapshot(baseVersion);
        MetaCategoryVersionSnapshotDto targetSnapshot = toVersionSnapshot(targetVersion);

        boolean nameChanged = !Objects.equals(baseSnapshot.getName(), targetSnapshot.getName());
        boolean descriptionChanged = !Objects.equals(baseSnapshot.getDescription(), targetSnapshot.getDescription());
        List<String> structureChangedPaths = new ArrayList<>();
        collectJsonDiffPaths(
                parseStructureJson(baseVersion.getStructureJson()),
                parseStructureJson(targetVersion.getStructureJson()),
                "structureJson",
                structureChangedPaths
        );
        boolean structureChanged = !structureChangedPaths.isEmpty();

        List<String> changedFields = new ArrayList<>();
        if (nameChanged) {
            changedFields.add("name");
        }
        if (descriptionChanged) {
            changedFields.add("description");
        }
        if (structureChanged) {
            changedFields.add("structureJson");
        }

        MetaCategoryVersionCompareDiffDto diff = new MetaCategoryVersionCompareDiffDto();
        diff.setSameVersion(baseVersion.getId().equals(targetVersion.getId()));
        diff.setNameChanged(nameChanged);
        diff.setDescriptionChanged(descriptionChanged);
        diff.setStructureChanged(structureChanged);
        diff.setStructureChangedPaths(structureChangedPaths);
        diff.setChangedFields(changedFields);

        MetaCategoryVersionCompareDto result = new MetaCategoryVersionCompareDto();
        result.setCategoryId(def.getId());
        result.setCategoryCode(def.getCodeKey());
        result.setBusinessDomain(def.getBusinessDomain());
        result.setBaseVersion(baseSnapshot);
        result.setTargetVersion(targetSnapshot);
        result.setDiff(diff);
        return result;
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

        Map<UUID, String> latestNameByDefId = versionRepository.findByCategoryDefInAndIsLatestTrue(all).stream()
                .filter(v -> v.getCategoryDef() != null)
                .collect(Collectors.toMap(
                        v -> v.getCategoryDef().getId(),
                        v -> v.getDisplayName() == null || v.getDisplayName().isBlank() ? v.getCategoryDef().getCodeKey() : v.getDisplayName(),
                        (a, b) -> a
                ));

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
            String currentName = latestTitleOrCode(current, latestNameByDefId);

            if (parent == null || !byId.containsKey(parent.getId())) {
                current.setPath("/" + current.getCodeKey());
                current.setDepth((short) 0);
                current.setFullPathName(currentName);
            } else {
                String parentPath = parent.getPath() == null ? "/" + parent.getCodeKey() : parent.getPath();
                current.setPath(parentPath + "/" + current.getCodeKey());
                short parentDepth = parent.getDepth() == null ? 0 : parent.getDepth();
                current.setDepth((short) (parentDepth + 1));
                String parentFullPath = parent.getFullPathName() == null
                        ? latestTitleOrCode(parent, latestNameByDefId)
                        : parent.getFullPathName();
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

    private String latestTitleOrCode(MetaCategoryDef def, Map<UUID, String> latestNameByDefId) {
        if (def == null || def.getId() == null) {
            return null;
        }
        return latestNameByDefId.getOrDefault(def.getId(), def.getCodeKey());
    }

    private void applyParentMove(MetaCategoryDef root, MetaCategoryDef oldParent) {
        MetaCategoryDef newParent = root.getParent();

        List<MetaCategoryDef> subtree = new ArrayList<>();
        subtree.add(root);
        subtree.addAll(hierarchyRepository.findDescendantDefs(root.getId()));

        recalculateSubtreeMeta(root, newParent, subtree);
        rebuildSubtreeClosure(root, newParent, subtree);

        refreshParentLeafIfNeeded(oldParent);
        refreshParentLeafIfNeeded(newParent);
    }

    private void recalculateSubtreeMeta(MetaCategoryDef root, MetaCategoryDef newParent, List<MetaCategoryDef> subtree) {
        if (subtree == null || subtree.isEmpty()) {
            return;
        }

        Map<UUID, String> latestNameByDefId = versionRepository.findByCategoryDefInAndIsLatestTrue(subtree).stream()
                .filter(v -> v.getCategoryDef() != null)
                .collect(Collectors.toMap(
                        v -> v.getCategoryDef().getId(),
                        v -> v.getDisplayName() == null || v.getDisplayName().isBlank() ? v.getCategoryDef().getCodeKey() : v.getDisplayName(),
                        (a, b) -> a
                ));

        Map<UUID, MetaCategoryDef> byId = subtree.stream().collect(Collectors.toMap(MetaCategoryDef::getId, d -> d));
        Map<UUID, List<MetaCategoryDef>> childrenByParent = new HashMap<>();
        for (MetaCategoryDef d : subtree) {
            UUID p = d.getParent() == null ? null : d.getParent().getId();
            if (p != null && byId.containsKey(p)) {
                childrenByParent.computeIfAbsent(p, k -> new ArrayList<>()).add(d);
            }
        }

        String rootName = latestTitleOrCode(root, latestNameByDefId);
        if (newParent == null) {
            root.setPath("/" + root.getCodeKey());
            root.setDepth(resolveRootDepthBase());
            root.setFullPathName(rootName);
        } else {
            String parentPath = newParent.getPath();
            if (parentPath == null || parentPath.isBlank()) {
                parentPath = "/" + newParent.getCodeKey();
            }
            String parentName = newParent.getFullPathName();
            if (parentName == null || parentName.isBlank()) {
                parentName = versionRepository.findLatestByDef(newParent)
                        .map(MetaCategoryVersion::getDisplayName)
                        .filter(v -> !v.isBlank())
                        .orElse(newParent.getCodeKey());
            }
            short parentDepth = newParent.getDepth() == null ? 0 : newParent.getDepth();
            root.setPath(parentPath + "/" + root.getCodeKey());
            root.setDepth((short) (parentDepth + 1));
            root.setFullPathName(parentName + "/" + rootName);
        }

        Queue<MetaCategoryDef> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            MetaCategoryDef current = q.poll();
            List<MetaCategoryDef> children = childrenByParent.getOrDefault(current.getId(), List.of());
            current.setIsLeaf(children.isEmpty());

            for (MetaCategoryDef child : children) {
                String childName = latestTitleOrCode(child, latestNameByDefId);
                String currentPath = current.getPath() == null ? "/" + current.getCodeKey() : current.getPath();
                String currentName = current.getFullPathName() == null
                        ? latestTitleOrCode(current, latestNameByDefId)
                        : current.getFullPathName();
                short currentDepth = current.getDepth() == null ? 0 : current.getDepth();

                child.setPath(currentPath + "/" + child.getCodeKey());
                child.setDepth((short) (currentDepth + 1));
                child.setFullPathName(currentName + "/" + childName);
                q.add(child);
            }
        }

        defRepository.saveAll(subtree);
    }

    private void rebuildSubtreeClosure(MetaCategoryDef root, MetaCategoryDef newParent, List<MetaCategoryDef> subtree) {
        List<UUID> subtreeIds = subtree.stream().map(MetaCategoryDef::getId).toList();
        if (subtreeIds.isEmpty()) {
            return;
        }

        hierarchyRepository.deleteExternalLinksForDescendants(subtreeIds, subtreeIds);

        if (newParent == null) {
            return;
        }

        List<MetaCategoryDef> newAncestors = hierarchyRepository.findAncestorsByDescendant(newParent.getId());
        if (newAncestors.isEmpty()) {
            newAncestors = List.of(newParent);
        }

        List<CategoryHierarchy> rows = new ArrayList<>();
        for (MetaCategoryDef ancestor : newAncestors) {
            short ancestorDepth = ancestor.getDepth() == null ? 0 : ancestor.getDepth();
            for (MetaCategoryDef node : subtree) {
                if (ancestor.getId().equals(node.getId())) {
                    continue;
                }
                short nodeDepth = node.getDepth() == null ? ancestorDepth : node.getDepth();
                short distance = (short) Math.max(nodeDepth - ancestorDepth, 0);
                CategoryHierarchy one = new CategoryHierarchy();
                one.setAncestorDef(ancestor);
                one.setDescendantDef(node);
                one.setDistance(distance);
                rows.add(one);
            }
        }

        if (!rows.isEmpty()) {
            hierarchyRepository.saveAll(rows);
        }
    }

    private void refreshParentLeafIfNeeded(MetaCategoryDef parent) {
        if (parent == null || parent.getId() == null) {
            return;
        }
        if (isDeleted(parent)) {
            return;
        }
        boolean hasActiveChildren = defRepository.countActiveChildren(parent.getId()) > 0;
        boolean nextLeaf = !hasActiveChildren;
        if (!Objects.equals(parent.getIsLeaf(), nextLeaf)) {
            parent.setIsLeaf(nextLeaf);
            defRepository.save(parent);
        }
    }

    private int nextSort(UUID parentId) {
        Integer maxSort = defRepository.findMaxSortByParentId(parentId);
        int base = maxSort == null ? 0 : maxSort;
        return base + 1;
    }

    private void normalizeSiblingOrders(UUID parentId) {
        List<MetaCategoryDef> siblings = defRepository.findActiveSiblingsByParentId(parentId);
        if (siblings.isEmpty()) {
            return;
        }
        int expected = 1;
        boolean changed = false;
        for (MetaCategoryDef sibling : siblings) {
            if (!Objects.equals(sibling.getSortOrder(), expected)) {
                sibling.setSortOrder(expected);
                changed = true;
            }
            expected++;
        }
        if (changed) {
            defRepository.saveAll(siblings);
        }
    }

    private void applyCreateNodeMeta(MetaCategoryDef def, MetaCategoryDef parent, String displayName) {
        if (parent == null) {
            def.setPath("/" + def.getCodeKey());
            def.setDepth(resolveRootDepthBase());
            def.setFullPathName(displayName);
            def.setIsLeaf(Boolean.TRUE);
            defRepository.save(def);
            return;
        }

        String parentPath = parent.getPath();
        if (parentPath == null || parentPath.isBlank()) {
            parentPath = "/" + parent.getCodeKey();
        }

        String parentName = parent.getFullPathName();
        if (parentName == null || parentName.isBlank()) {
            parentName = versionRepository.findLatestByDef(parent)
                    .map(MetaCategoryVersion::getDisplayName)
                    .filter(v -> !v.isBlank())
                    .orElse(parent.getCodeKey());
        }

        short parentDepth = parent.getDepth() == null ? 0 : parent.getDepth();
        def.setPath(parentPath + "/" + def.getCodeKey());
        def.setDepth((short) (parentDepth + 1));
        def.setFullPathName(parentName + "/" + displayName);
        def.setIsLeaf(Boolean.TRUE);
        defRepository.save(def);
    }

    private void insertClosureForNewNode(MetaCategoryDef def, MetaCategoryDef parent) {
        List<CategoryHierarchy> rows = new ArrayList<>();

        CategoryHierarchy self = new CategoryHierarchy();
        self.setAncestorDef(def);
        self.setDescendantDef(def);
        self.setDistance((short) 0);
        rows.add(self);

        if (parent != null) {
            List<MetaCategoryDef> ancestors = hierarchyRepository.findAncestorsByDescendant(parent.getId());
            if (ancestors.isEmpty()) {
                ancestors = List.of(parent);
            }

            short distance = 1;
            for (int i = ancestors.size() - 1; i >= 0; i--) {
                MetaCategoryDef ancestor = ancestors.get(i);
                CategoryHierarchy one = new CategoryHierarchy();
                one.setAncestorDef(ancestor);
                one.setDescendantDef(def);
                one.setDistance(distance++);
                rows.add(one);
            }
        }

        hierarchyRepository.saveAll(rows);
    }

    private void ensureNoCycle(UUID currentId, UUID nextParentId) {
        List<UUID> descendants = hierarchyRepository.findDescendantIdsIncludingSelf(currentId);
        if (descendants.contains(nextParentId)) {
            throw new IllegalArgumentException("parentId cannot be a descendant of current node");
        }
    }

    private MetaCategoryVersion loadVersion(UUID versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("category version not found: id=" + versionId));
    }

    private void ensureVersionBelongsToCategory(MetaCategoryDef def, MetaCategoryVersion version, String fieldName) {
        if (version.getCategoryDef() == null || version.getCategoryDef().getId() == null) {
            throw new IllegalArgumentException(fieldName + " is invalid: category version missing categoryDef");
        }
        if (!def.getId().equals(version.getCategoryDef().getId())) {
            throw new IllegalArgumentException(fieldName + " does not belong to category: categoryId=" + def.getId());
        }
    }

    private MetaCategoryVersionSnapshotDto toVersionSnapshot(MetaCategoryVersion version) {
        MetaCategoryVersionSnapshotDto dto = new MetaCategoryVersionSnapshotDto();
        dto.setVersionId(version.getId());
        dto.setVersionNo(version.getVersionNo());
        dto.setVersionDate(version.getCreatedAt());
        dto.setName(version.getDisplayName());
        dto.setDescription(readDescription(version.getStructureJson()));
        dto.setUpdatedBy(version.getCreatedBy());
        return dto;
    }

    private JsonNode parseStructureJson(String structureJson) {
        String normalized = trimToNull(structureJson);
        if (normalized == null) {
            return null;
        }
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception ex) {
            return null;
        }
    }

    private void collectJsonDiffPaths(JsonNode baseNode, JsonNode targetNode, String currentPath, List<String> changedPaths) {
        if (baseNode == null && targetNode == null) {
            return;
        }
        if (baseNode == null || targetNode == null) {
            changedPaths.add(currentPath);
            return;
        }

        if (baseNode.isObject() && targetNode.isObject()) {
            Set<String> allFields = new TreeSet<>();
            baseNode.fieldNames().forEachRemaining(allFields::add);
            targetNode.fieldNames().forEachRemaining(allFields::add);
            for (String field : allFields) {
                collectJsonDiffPaths(baseNode.get(field), targetNode.get(field), currentPath + "." + field, changedPaths);
            }
            return;
        }

        if (baseNode.isArray() && targetNode.isArray()) {
            int maxSize = Math.max(baseNode.size(), targetNode.size());
            for (int i = 0; i < maxSize; i++) {
                JsonNode left = i < baseNode.size() ? baseNode.get(i) : null;
                JsonNode right = i < targetNode.size() ? targetNode.get(i) : null;
                collectJsonDiffPaths(left, right, currentPath + "[" + i + "]", changedPaths);
            }
            return;
        }

        if (!baseNode.equals(targetNode)) {
            changedPaths.add(currentPath);
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
