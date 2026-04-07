package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.attribute.version.exception.CategoryConflictException;
import com.plm.attribute.version.exception.CategoryNotFoundException;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchCopyOptionsDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchDeleteItemResultDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchDeleteRequestDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchDeleteResponseDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferItemResultDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferOperationDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferRequestDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferResponseDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferTopologyFinalParentMappingDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferTopologyItemResultDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferTopologyOperationDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferTopologyRequestDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferTopologyResponseDto;
import com.plm.common.api.dto.category.CreateCategoryCodePreviewRequestDto;
import com.plm.common.api.dto.category.CreateCategoryCodePreviewResponseDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.MetaCategoryCodeMappingDto;
import com.plm.common.api.dto.category.MetaCategoryCopySourceMappingDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
import com.plm.common.api.dto.code.CodeRulePreviewResponseDto;
import com.plm.common.api.dto.category.version.MetaCategoryLatestVersionDto;
import com.plm.common.api.dto.category.version.MetaCategoryVersionCompareDiffDto;
import com.plm.common.api.dto.category.version.MetaCategoryVersionCompareDto;
import com.plm.common.api.dto.category.version.MetaCategoryVersionHistoryDto;
import com.plm.common.api.dto.category.version.MetaCategoryVersionSnapshotDto;
import com.plm.common.api.dto.category.UpdateCategoryRequestDto;
import com.plm.common.version.domain.CategoryHierarchy;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MetaCategoryCrudService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";
    private static final String STATUS_DELETED = "deleted";
    private static final short ROOT_DEPTH_BASE = 1;
    private static final int BATCH_DELETE_MAX_SIZE = 200;
    private static final String CODE_ALREADY_DELETED = "ALREADY_DELETED";
    private static final String CODE_ATOMIC_ROLLBACK = "ATOMIC_ROLLBACK";
    private static final String CODE_ATOMIC_ABORTED = "ATOMIC_ABORTED";
    private static final int BATCH_TRANSFER_MAX_SIZE = 200;
    private static final String ACTION_MOVE = "MOVE";
    private static final String ACTION_COPY = "COPY";
    private static final String CODE_SOURCE_OVERLAP_NORMALIZED = "SOURCE_OVERLAP_NORMALIZED";
    private static final String PLANNING_MODE_TOPOLOGY_AWARE = "TOPOLOGY_AWARE";
    private static final String ORDERING_STRATEGY_CLIENT_ORDER = "CLIENT_ORDER";
    private static final String ORDERING_STRATEGY_TOPOLOGICAL_BOTTOM_UP = "TOPOLOGICAL_BOTTOM_UP";

    private static final Comparator<MetaCategoryDef> CATEGORY_TREE_ORDER = Comparator
            .comparing((MetaCategoryDef def) -> def.getDepth() == null ? Short.MAX_VALUE : def.getDepth())
            .thenComparing(def -> def.getSortOrder() == null ? Integer.MAX_VALUE : def.getSortOrder())
            .thenComparing(MetaCategoryDef::getCodeKey);

    private final MetaCategoryDefRepository defRepository;
    private final MetaCategoryVersionRepository versionRepository;
    private final CategoryHierarchyRepository hierarchyRepository;
    private final MetaCodeRuleService metaCodeRuleService;
    private final MetaCodeRuleSetService metaCodeRuleSetService;
    private final TransactionTemplate requiresNewTxTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private enum BusinessDomain {
        PRODUCT,
        MATERIAL,
        DEVICE,
        BOM,
        PROCESS,
        DOCUMENT,
        TEST,
        EXPERIMENT
    }

    private static final class CopyOptionsResolved {
        private final String namePolicy;
        private final String defaultStatus;

        private CopyOptionsResolved(String namePolicy, String defaultStatus) {
            this.namePolicy = namePolicy;
            this.defaultStatus = defaultStatus;
        }
    }

    private static final class TransferOperationContext {
        private final int index;
        private final String clientOperationId;
        private final UUID sourceNodeId;
        private final UUID targetParentId;

        private TransferOperationContext(int index, String clientOperationId, UUID sourceNodeId, UUID targetParentId) {
            this.index = index;
            this.clientOperationId = clientOperationId;
            this.sourceNodeId = sourceNodeId;
            this.targetParentId = targetParentId;
        }
    }

    private static final class TransferPlanItem {
        private final TransferOperationContext operation;
        private final String action;
        private UUID normalizedSourceNodeId;
        private Integer affectedNodeCount;
        private String failureCode;
        private String failureMessage;
        private List<String> warnings = new ArrayList<>();
        private boolean normalized;

        private TransferPlanItem(TransferOperationContext operation, String action) {
            this.operation = operation;
            this.action = action;
            this.normalizedSourceNodeId = operation.sourceNodeId;
            this.affectedNodeCount = 0;
        }

        private boolean hasFailure() {
            return failureCode != null;
        }
    }

    private static final class TransferPlan {
        private final String businessDomain;
        private final String action;
        private final boolean dryRun;
        private final boolean atomic;
        private final String operator;
        private final CopyOptionsResolved copyOptions;
        private final List<TransferPlanItem> items;
        private final List<String> warnings;

        private TransferPlan(String businessDomain,
                             String action,
                             boolean dryRun,
                             boolean atomic,
                             String operator,
                             CopyOptionsResolved copyOptions,
                             List<TransferPlanItem> items,
                             List<String> warnings) {
            this.businessDomain = businessDomain;
            this.action = action;
            this.dryRun = dryRun;
            this.atomic = atomic;
            this.operator = operator;
            this.copyOptions = copyOptions;
            this.items = items;
            this.warnings = warnings;
        }
    }

    private static final class TransferExecutionOutcome {
        private final int affectedNodeCount;
        private final List<UUID> movedIds;
        private final UUID createdRootId;
        private final List<UUID> createdIds;
        private final UUID copiedFromCategoryId;
        private final List<MetaCategoryCopySourceMappingDto> sourceMappings;
        private final List<MetaCategoryCodeMappingDto> codeMappings;

        private TransferExecutionOutcome(int affectedNodeCount,
                                         List<UUID> movedIds,
                                         UUID createdRootId,
                                         List<UUID> createdIds,
                                         UUID copiedFromCategoryId,
                                         List<MetaCategoryCopySourceMappingDto> sourceMappings,
                                         List<MetaCategoryCodeMappingDto> codeMappings) {
            this.affectedNodeCount = affectedNodeCount;
            this.movedIds = movedIds;
            this.createdRootId = createdRootId;
            this.createdIds = createdIds;
            this.copiedFromCategoryId = copiedFromCategoryId;
            this.sourceMappings = sourceMappings;
            this.codeMappings = codeMappings;
        }
    }

    private static final class TopologyOperationContext {
        private final int index;
        private final String operationId;
        private final UUID sourceNodeId;
        private final UUID targetParentId;
        private final List<String> dependsOnOperationIds;
        private final boolean allowDescendantFirstSplit;
        private final UUID expectedSourceParentId;

        private TopologyOperationContext(int index,
                                         String operationId,
                                         UUID sourceNodeId,
                                         UUID targetParentId,
                                         List<String> dependsOnOperationIds,
                                         boolean allowDescendantFirstSplit,
                                         UUID expectedSourceParentId) {
            this.index = index;
            this.operationId = operationId;
            this.sourceNodeId = sourceNodeId;
            this.targetParentId = targetParentId;
            this.dependsOnOperationIds = dependsOnOperationIds;
            this.allowDescendantFirstSplit = allowDescendantFirstSplit;
            this.expectedSourceParentId = expectedSourceParentId;
        }
    }

    private static final class TopologyPlanItem {
        private final TopologyOperationContext operation;
        private UUID effectiveSourceParentIdBefore;
        private UUID effectiveTargetParentId;
        private final List<String> resolvedDependsOn = new ArrayList<>();
        private String failureCode;
        private String failureMessage;

        private TopologyPlanItem(TopologyOperationContext operation) {
            this.operation = operation;
        }

        private boolean hasFailure() {
            return failureCode != null;
        }
    }

    private static final class TopologyPlan {
        private final String businessDomain;
        private final boolean dryRun;
        private final boolean atomic;
        private final String planningMode;
        private final List<TopologyPlanItem> items;
        private final List<String> planningWarnings;
        private final List<String> resolvedOrder;
        private final Map<UUID, UUID> finalParentMappings;

        private TopologyPlan(String businessDomain,
                             boolean dryRun,
                             boolean atomic,
                             String planningMode,
                             List<TopologyPlanItem> items,
                             List<String> planningWarnings,
                             List<String> resolvedOrder,
                             Map<UUID, UUID> finalParentMappings) {
            this.businessDomain = businessDomain;
            this.dryRun = dryRun;
            this.atomic = atomic;
            this.planningMode = planningMode;
            this.items = items;
            this.planningWarnings = planningWarnings;
            this.resolvedOrder = resolvedOrder;
            this.finalParentMappings = finalParentMappings;
        }
    }

    public MetaCategoryCrudService(MetaCategoryDefRepository defRepository,
                                   MetaCategoryVersionRepository versionRepository,
                                   CategoryHierarchyRepository hierarchyRepository,
                                   MetaCodeRuleService metaCodeRuleService,
                                   MetaCodeRuleSetService metaCodeRuleSetService,
                                   PlatformTransactionManager transactionManager) {
        this.defRepository = defRepository;
        this.versionRepository = versionRepository;
        this.hierarchyRepository = hierarchyRepository;
        this.metaCodeRuleService = metaCodeRuleService;
        this.metaCodeRuleSetService = metaCodeRuleSetService;

        PlatformTransactionManager nonNullTransactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        DefaultTransactionDefinition requiresNewDefinition = new DefaultTransactionDefinition();
        requiresNewDefinition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTxTemplate = new TransactionTemplate(nonNullTransactionManager, requiresNewDefinition);
    }

    @Transactional
    public MetaCategoryDetailDto create(CreateCategoryRequestDto req, String operator) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String name = requireName(req.getName());
        String businessDomain = normalizeBusinessDomain(req.getBusinessDomain());
        String status = mapApiStatusToDb(req.getStatus(), true);

        MetaCategoryDef parent = resolveCreateParent(req.getParentId(), businessDomain);

        MetaCategoryDef def = new MetaCategoryDef();
        MetaCodeRuleService.GeneratedCodeResult generatedCode = resolveCategoryCodeForCreate(req, businessDomain, parent, null, operator);
        String code = generatedCode.code();
        if (defRepository.existsByBusinessDomainAndCodeKey(businessDomain, code)) {
            throw new IllegalArgumentException("category already exists: businessDomain=" + businessDomain + ", code=" + code);
        }
        def.setCodeKey(code);
        def.setBusinessDomain(businessDomain);
        def.setParent(parent);
        def.setStatus(status);
        def.setCodeKeyManualOverride(generatedCode.manualOverride());
        def.setCodeKeyFrozen(generatedCode.frozen());
        def.setGeneratedRuleCode(generatedCode.ruleCode());
        def.setGeneratedRuleVersionNo(generatedCode.ruleVersion());
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

    @Transactional(readOnly = true)
    public CreateCategoryCodePreviewResponseDto previewCreateCode(CreateCategoryCodePreviewRequestDto req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }

        String businessDomain = normalizeBusinessDomain(req.getBusinessDomain());
        MetaCategoryDef parent = resolveCreateParent(req.getParentId(), businessDomain);
        LinkedHashMap<String, String> context = buildCategoryCodeContext(businessDomain, parent);
        String manualCode = trimToNull(req.getManualCode());
        String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(businessDomain);

        CodeRulePreviewRequestDto previewRequest = new CodeRulePreviewRequestDto();
        previewRequest.setContext(context);
        previewRequest.setManualCode(manualCode);
        previewRequest.setCount(normalizePreviewCount(req.getCount()));

        CodeRulePreviewResponseDto preview = metaCodeRuleService.preview(ruleCode, previewRequest);
        CodeRuleDetailDto ruleDetail = metaCodeRuleService.detail(ruleCode);

        CreateCategoryCodePreviewResponseDto response = new CreateCategoryCodePreviewResponseDto();
        response.setBusinessDomain(businessDomain);
        response.setRuleCode(ruleCode);
        response.setGenerationMode(manualCode == null ? "AUTO" : "MANUAL");
        response.setAllowManualOverride(Boolean.TRUE.equals(ruleDetail.getAllowManualOverride()));
        response.setSuggestedCode(preview.getExamples() == null || preview.getExamples().isEmpty() ? null : preview.getExamples().get(0));
        response.setExamples(preview.getExamples());
        response.setWarnings(preview.getWarnings());
        response.setResolvedContext(preview.getResolvedContext());
        response.setResolvedSequenceScope(preview.getResolvedSequenceScope());
        response.setResolvedPeriodKey(preview.getResolvedPeriodKey());
        response.setPreviewStale(Boolean.FALSE);
        return response;
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
            targets = defRepository.findAllById(Objects.requireNonNull(descendantIds, "descendantIds"));
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
        defRepository.saveAll(Objects.requireNonNull(targets, "targets"));

        refreshParentLeafIfNeeded(oldParent);
        normalizeSiblingOrders(oldParent == null ? null : oldParent.getId());
        return changed;
    }

    public MetaCategoryBatchDeleteResponseDto batchDelete(MetaCategoryBatchDeleteRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }

        List<UUID> ids = normalizeBatchIds(request.getIds());
        boolean cascade = Boolean.TRUE.equals(request.getCascade());
        boolean confirm = Boolean.TRUE.equals(request.getConfirm());
        boolean atomic = Boolean.TRUE.equals(request.getAtomic());
        boolean dryRun = Boolean.TRUE.equals(request.getDryRun());
        String operator = request.getOperator();

        if (dryRun) {
            return batchDeleteDryRun(ids, cascade, confirm);
        }

        if (atomic) {
            return batchDeleteAtomic(ids, cascade, confirm, operator);
        }
        return batchDeleteNonAtomic(ids, cascade, confirm, operator);
    }

    public MetaCategoryBatchTransferResponseDto batchTransfer(MetaCategoryBatchTransferRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }

        TransferPlan plan = prepareTransferPlan(request);
        if (plan.dryRun) {
            return buildTransferResponse(plan, buildDryRunResults(plan));
        }

        if (plan.atomic) {
            return batchTransferAtomic(plan);
        }
        return batchTransferNonAtomic(plan);
    }

    public MetaCategoryBatchTransferTopologyResponseDto batchTransferTopology(MetaCategoryBatchTransferTopologyRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }

        TopologyPlan plan = prepareTopologyPlan(request);
        if (plan.dryRun || plan.items.stream().anyMatch(TopologyPlanItem::hasFailure)) {
            return buildTopologyResponse(plan);
        }

        List<TopologyPlanItem> executedItems = new ArrayList<>();
        TopologyPlanItem[] failedAt = new TopologyPlanItem[1];
        RuntimeException[] failedException = new RuntimeException[1];

        try {
            requiresNewTxTemplate.executeWithoutResult(status -> {
                for (TopologyPlanItem item : plan.items) {
                    try {
                        MetaCategoryDef source = loadActiveCategory(item.operation.sourceNodeId, "CATEGORY_NOT_FOUND");
                        UUID currentParentId = source.getParent() == null ? null : source.getParent().getId();
                        if (!Objects.equals(currentParentId, item.effectiveSourceParentIdBefore)) {
                            throw new CategoryConflictException(
                                    "CATEGORY_EXPECTED_PARENT_MISMATCH",
                                    "source parent changed since planning: sourceId=" + item.operation.sourceNodeId
                            );
                        }
                        executeMove(plan.businessDomain, item.operation.sourceNodeId, item.operation.targetParentId);
                        executedItems.add(item);
                    } catch (RuntimeException ex) {
                        failedAt[0] = item;
                        failedException[0] = ex;
                        status.setRollbackOnly();
                        throw ex;
                    }
                }
            });
        } catch (RuntimeException ex) {
            TopologyPlanItem effectiveFailedItem = failedAt[0];
            RuntimeException effectiveFailure = failedException[0] == null ? ex : failedException[0];
            if (effectiveFailedItem == null) {
                if (!executedItems.isEmpty()) {
                    effectiveFailedItem = executedItems.get(executedItems.size() - 1);
                } else if (!plan.items.isEmpty()) {
                    effectiveFailedItem = plan.items.get(0);
                }
            }
            return buildTopologyResponse(plan, executedItems, effectiveFailedItem, effectiveFailure);
        }

        return buildTopologyResponse(plan);
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
        dto.setLevel(resolveLevel(def.getPath(), def.getDepth()));
        dto.setSort(def.getSortOrder());
        dto.setCopiedFromCategoryId(def.getCopiedFromCategoryId());
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
            root.setDepth(ROOT_DEPTH_BASE);
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
            UUID parentId = Objects.requireNonNull(newParent.getId(), "newParent.id");
            newAncestors = List.of(defRepository.getReferenceById(parentId));
        }

        Map<UUID, MetaCategoryDef> subtreeRefs = subtreeIds.stream()
                .collect(Collectors.toMap(Function.identity(), defRepository::getReferenceById));

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
                one.setDescendantDef(subtreeRefs.get(node.getId()));
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

        UUID parentId = Objects.requireNonNull(parent.getId(), "parent.id");
        MetaCategoryDef managedParent = defRepository.findById(parentId).orElse(null);
        if (managedParent == null || isDeleted(managedParent)) {
            return;
        }

        boolean hasActiveChildren = defRepository.countActiveChildren(managedParent.getId()) > 0;
        boolean nextLeaf = !hasActiveChildren;
        if (!Objects.equals(managedParent.getIsLeaf(), nextLeaf)) {
            managedParent.setIsLeaf(nextLeaf);
            defRepository.save(managedParent);
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
            def.setDepth(ROOT_DEPTH_BASE);
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
        UUID nonNullVersionId = Objects.requireNonNull(versionId, "versionId");
        return versionRepository.findById(nonNullVersionId)
                .orElseThrow(() -> new IllegalArgumentException("category version not found: id=" + versionId));
    }

    private TransferPlan prepareTransferPlan(MetaCategoryBatchTransferRequestDto request) {
        String businessDomain = normalizeBusinessDomain(request.getBusinessDomain());
        String action = normalizeTransferAction(request.getAction());
        boolean dryRun = Boolean.TRUE.equals(request.getDryRun());
        boolean atomic = resolveAtomicDefaultTrue(request.getAtomic());
        String operator = normalizeOperator(request.getOperator());
        CopyOptionsResolved copyOptions = resolveCopyOptions(action, request.getCopyOptions());
        List<TransferOperationContext> operations = normalizeTransferOperations(request.getOperations(), request.getTargetParentId());

        Map<UUID, MetaCategoryDef> sourceMap = loadCategoryMap(operations.stream().map(op -> op.sourceNodeId).toList());
        Map<UUID, MetaCategoryDef> targetMap = loadCategoryMap(
                operations.stream().map(op -> op.targetParentId).filter(Objects::nonNull).toList()
        );
        Map<UUID, Set<UUID>> descendantMap = loadDescendantMap(sourceMap.keySet());

        List<TransferPlanItem> items = new ArrayList<>();
        for (TransferOperationContext operation : operations) {
            TransferPlanItem item = new TransferPlanItem(operation, action);
            MetaCategoryDef source = sourceMap.get(operation.sourceNodeId);
            MetaCategoryDef target = operation.targetParentId == null ? null : targetMap.get(operation.targetParentId);
            validatePlanItem(item, source, target, businessDomain, descendantMap);
            if (!item.hasFailure()) {
                item.affectedNodeCount = descendantMap.getOrDefault(operation.sourceNodeId, Set.of(operation.sourceNodeId)).size();
            }
            items.add(item);
        }

        applySourceOverlapRules(items, descendantMap);

        int normalizedCount = (int) items.stream().filter(item -> item.normalized).count();
        List<String> warnings = normalizedCount == 0
                ? List.of()
                : List.of(normalizedCount + " child operation normalized because ancestor already included");

        return new TransferPlan(businessDomain, action, dryRun, atomic, operator, copyOptions, items, warnings);
    }

    private TopologyPlan prepareTopologyPlan(MetaCategoryBatchTransferTopologyRequestDto request) {
        String businessDomain = normalizeBusinessDomain(request.getBusinessDomain());
        String action = normalizeTransferAction(request.getAction());
        if (!ACTION_MOVE.equals(action)) {
            throw new CategoryConflictException("CATEGORY_TOPOLOGY_ACTION_UNSUPPORTED", "topology batch transfer only supports MOVE");
        }

        boolean dryRun = Boolean.TRUE.equals(request.getDryRun());
        boolean atomic = resolveAtomicDefaultTrue(request.getAtomic());
        if (!atomic) {
            throw new IllegalArgumentException("atomic=false is not supported for topology batch transfer");
        }

        String planningMode = normalizeTopologyPlanningMode(request.getPlanningMode());
        String orderingStrategy = normalizeTopologyOrderingStrategy(request.getOrderingStrategy());
        boolean strictDependencyValidation = request.getStrictDependencyValidation() == null || Boolean.TRUE.equals(request.getStrictDependencyValidation());
        List<TopologyOperationContext> operations = normalizeTopologyOperations(request.getOperations());

        Map<String, TopologyPlanItem> itemByOperationId = new LinkedHashMap<>();
        List<TopologyPlanItem> items = operations.stream().map(TopologyPlanItem::new).toList();
        for (TopologyPlanItem item : items) {
            itemByOperationId.put(item.operation.operationId, item);
        }

        Map<UUID, MetaCategoryDef> sourceMap = loadCategoryMap(operations.stream().map(op -> op.sourceNodeId).toList());
        Map<UUID, MetaCategoryDef> targetMap = loadCategoryMap(operations.stream().map(op -> op.targetParentId).filter(Objects::nonNull).toList());
        Map<UUID, Set<UUID>> descendantMap = loadDescendantMap(sourceMap.keySet());

        for (TopologyPlanItem item : items) {
            MetaCategoryDef target = item.operation.targetParentId == null ? null : targetMap.get(item.operation.targetParentId);
            validateTopologyItem(item, sourceMap.get(item.operation.sourceNodeId), target, businessDomain);
        }

        validateTopologyDependencies(items, itemByOperationId, orderingStrategy, strictDependencyValidation);
        validateTopologySplitOrdering(items, descendantMap);

        Map<UUID, MetaCategoryDef> effectiveNodeMap = loadCategoryMapWithAncestors(collectTopologyRelevantIds(operations));
        Map<UUID, UUID> effectiveParentMap = new HashMap<>();
        for (MetaCategoryDef def : effectiveNodeMap.values()) {
            effectiveParentMap.put(def.getId(), def.getParent() == null ? null : def.getParent().getId());
        }

        validateEffectiveTopologyTargets(items, itemByOperationId, effectiveParentMap);

        List<String> resolvedOrder = new ArrayList<>();
        Map<UUID, UUID> finalParentMappings = new LinkedHashMap<>();
        for (TopologyPlanItem item : items) {
            simulateTopologyItem(item, itemByOperationId, effectiveParentMap, resolvedOrder, finalParentMappings);
        }

        return new TopologyPlan(
                businessDomain,
                dryRun,
                atomic,
                planningMode,
                items,
                List.of(),
                resolvedOrder,
                finalParentMappings
        );
    }

    private MetaCategoryBatchTransferResponseDto batchTransferNonAtomic(TransferPlan plan) {
        List<MetaCategoryBatchTransferItemResultDto> results = new ArrayList<>();
        for (TransferPlanItem item : plan.items) {
            if (item.hasFailure()) {
                results.add(buildTransferFailureResult(item, item.failureCode, item.failureMessage));
                continue;
            }
            if (item.normalized) {
                results.add(buildTransferNormalizedResult(item));
                continue;
            }
            try {
                TransferExecutionOutcome outcome = requiresNewTxTemplate.execute(status -> executeTransfer(plan, item));
                results.add(buildTransferSuccessResult(item, outcome));
            } catch (RuntimeException ex) {
                results.add(buildTransferFailureResult(item, resolveBatchErrorCode(ex), ex.getMessage()));
            }
        }
        return buildTransferResponse(plan, results);
    }

    private void validateTopologyItem(TopologyPlanItem item,
                                      MetaCategoryDef source,
                                      MetaCategoryDef target,
                                      String businessDomain) {
        if (source == null) {
            item.failureCode = "CATEGORY_NOT_FOUND";
            item.failureMessage = "source category not found: id=" + item.operation.sourceNodeId;
            return;
        }
        if (isDeleted(source)) {
            item.failureCode = "CATEGORY_DELETED";
            item.failureMessage = "source category is deleted: id=" + source.getId();
            return;
        }
        if (!Objects.equals(source.getBusinessDomain(), businessDomain)) {
            item.failureCode = "CATEGORY_DOMAIN_MISMATCH";
            item.failureMessage = "source and request businessDomain mismatch: sourceId=" + source.getId();
            return;
        }

        UUID currentParentId = source.getParent() == null ? null : source.getParent().getId();
        if (item.operation.expectedSourceParentId != null
            && !Objects.equals(currentParentId, item.operation.expectedSourceParentId)) {
            item.failureCode = "CATEGORY_EXPECTED_PARENT_MISMATCH";
            item.failureMessage = "source parent does not match expected parent: sourceId=" + source.getId();
            return;
        }

        if (item.operation.targetParentId == null) {
            return;
        }
        if (target == null) {
            item.failureCode = "CATEGORY_TARGET_PARENT_NOT_FOUND";
            item.failureMessage = "target parent not found: id=" + item.operation.targetParentId;
            return;
        }
        if (isDeleted(target)) {
            item.failureCode = "CATEGORY_DELETED";
            item.failureMessage = "target parent is deleted: id=" + target.getId();
            return;
        }
        if (!Objects.equals(target.getBusinessDomain(), businessDomain)) {
            item.failureCode = "CATEGORY_DOMAIN_MISMATCH";
            item.failureMessage = "target parent and request businessDomain mismatch: targetParentId=" + target.getId();
            return;
        }
        if (source.getId().equals(target.getId())) {
            item.failureCode = "CATEGORY_TARGET_IS_SELF";
            item.failureMessage = "target parent cannot be self";
        }
    }

    private void validateTopologyDependencies(List<TopologyPlanItem> items,
                                              Map<String, TopologyPlanItem> itemByOperationId,
                                              String orderingStrategy,
                                              boolean strictDependencyValidation) {
        for (TopologyPlanItem item : items) {
            LinkedHashSet<String> dependencies = new LinkedHashSet<>();
            for (String dependencyId : item.operation.dependsOnOperationIds) {
                String normalized = trimToNull(dependencyId);
                if (normalized == null) {
                    continue;
                }
                if (normalized.equals(item.operation.operationId)) {
                    item.failureCode = "CATEGORY_BATCH_DEPENDENCY_CYCLE";
                    item.failureMessage = "operation cannot depend on itself: operationId=" + item.operation.operationId;
                    break;
                }
                if (!itemByOperationId.containsKey(normalized)) {
                    item.failureCode = "CATEGORY_DEPENDENCY_UNSATISFIED";
                    item.failureMessage = "dependency operation not found: operationId=" + normalized;
                    break;
                }
                dependencies.add(normalized);
            }
            item.resolvedDependsOn.clear();
            item.resolvedDependsOn.addAll(dependencies);
        }

        detectTopologyDependencyCycles(items, itemByOperationId);

        if (strictDependencyValidation && ORDERING_STRATEGY_CLIENT_ORDER.equals(orderingStrategy)) {
            Map<String, Integer> operationIndexById = items.stream()
                    .collect(Collectors.toMap(item -> item.operation.operationId, item -> item.operation.index));
            for (TopologyPlanItem item : items) {
                if (item.hasFailure()) {
                    continue;
                }
                for (String dependencyId : item.resolvedDependsOn) {
                    Integer dependencyIndex = operationIndexById.get(dependencyId);
                    if (dependencyIndex != null && dependencyIndex >= item.operation.index) {
                        item.failureCode = "CATEGORY_OPERATION_ORDER_INVALID";
                        item.failureMessage = "dependency must appear before operation in CLIENT_ORDER: operationId=" + item.operation.operationId;
                        break;
                    }
                }
            }
        }
    }

    private void detectTopologyDependencyCycles(List<TopologyPlanItem> items,
                                                Map<String, TopologyPlanItem> itemByOperationId) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Set<String> cycleNodes = new HashSet<>();

        for (TopologyPlanItem item : items) {
            dfsDetectTopologyDependencyCycle(item.operation.operationId, itemByOperationId, visiting, visited, cycleNodes);
        }

        if (!cycleNodes.isEmpty()) {
            for (TopologyPlanItem item : items) {
                if (cycleNodes.contains(item.operation.operationId)) {
                    item.failureCode = "CATEGORY_BATCH_DEPENDENCY_CYCLE";
                    item.failureMessage = "dependency cycle detected: operationId=" + item.operation.operationId;
                }
            }
        }
    }

    private void dfsDetectTopologyDependencyCycle(String operationId,
                                                  Map<String, TopologyPlanItem> itemByOperationId,
                                                  Set<String> visiting,
                                                  Set<String> visited,
                                                  Set<String> cycleNodes) {
        if (operationId == null || visited.contains(operationId) || cycleNodes.contains(operationId)) {
            return;
        }
        if (!visiting.add(operationId)) {
            cycleNodes.add(operationId);
            return;
        }

        TopologyPlanItem item = itemByOperationId.get(operationId);
        if (item != null) {
            for (String dependencyId : item.resolvedDependsOn) {
                if (visiting.contains(dependencyId)) {
                    cycleNodes.add(operationId);
                    cycleNodes.add(dependencyId);
                    continue;
                }
                dfsDetectTopologyDependencyCycle(dependencyId, itemByOperationId, visiting, visited, cycleNodes);
                if (cycleNodes.contains(dependencyId)) {
                    cycleNodes.add(operationId);
                }
            }
        }

        visiting.remove(operationId);
        visited.add(operationId);
    }

    private void validateTopologySplitOrdering(List<TopologyPlanItem> items, Map<UUID, Set<UUID>> descendantMap) {
        for (TopologyPlanItem ancestorItem : items) {
            if (ancestorItem.hasFailure()) {
                continue;
            }
            for (TopologyPlanItem descendantItem : items) {
                if (ancestorItem == descendantItem || descendantItem.hasFailure()) {
                    continue;
                }
                if (!descendantMap.getOrDefault(ancestorItem.operation.sourceNodeId, Set.of()).contains(descendantItem.operation.sourceNodeId)) {
                    continue;
                }
                // Lower index means the client placed the operation earlier in the batch.
                // Descendant-first split requires the ancestor move to appear after its descendant move.
                if (ancestorItem.operation.index < descendantItem.operation.index) {
                    ancestorItem.failureCode = "CATEGORY_OPERATION_ORDER_INVALID";
                    ancestorItem.failureMessage = "ancestor operation must be ordered after descendant operation: operationId=" + ancestorItem.operation.operationId;
                    break;
                }
                if (!ancestorItem.operation.allowDescendantFirstSplit || !descendantItem.operation.allowDescendantFirstSplit) {
                    ancestorItem.failureCode = "CATEGORY_OPERATION_ORDER_INVALID";
                    ancestorItem.failureMessage = "descendant-first split requires allowDescendantFirstSplit=true: operationId=" + ancestorItem.operation.operationId;
                    break;
                }
            }
        }
    }

    private void validateEffectiveTopologyTargets(List<TopologyPlanItem> items,
                                                  Map<String, TopologyPlanItem> itemByOperationId,
                                                  Map<UUID, UUID> effectiveParentMap) {
        Map<UUID, UUID> simulatedParentMap = new HashMap<>(effectiveParentMap);
        for (TopologyPlanItem item : items) {
            if (item.hasFailure()) {
                continue;
            }

            boolean dependencyFailed = false;
            for (String dependencyId : item.resolvedDependsOn) {
                TopologyPlanItem dependencyItem = itemByOperationId.get(dependencyId);
                if (dependencyItem != null && dependencyItem.hasFailure()) {
                    dependencyFailed = true;
                    break;
                }
            }
            if (dependencyFailed || item.operation.targetParentId == null) {
                continue;
            }

            if (isEffectiveDescendant(item.operation.targetParentId, item.operation.sourceNodeId, simulatedParentMap)) {
                item.failureCode = "CATEGORY_EFFECTIVE_TARGET_IN_DESCENDANT";
                item.failureMessage = "target parent is inside source subtree in effective tree";
                continue;
            }

            simulatedParentMap.put(item.operation.sourceNodeId, item.operation.targetParentId);
        }
    }

    private Set<UUID> collectTopologyRelevantIds(List<TopologyOperationContext> operations) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (TopologyOperationContext operation : operations) {
            ids.add(operation.sourceNodeId);
            if (operation.targetParentId != null) {
                ids.add(operation.targetParentId);
            }
        }
        return ids;
    }

    private Map<UUID, MetaCategoryDef> loadCategoryMapWithAncestors(Set<UUID> ids) {
        Map<UUID, MetaCategoryDef> map = new HashMap<>();
        for (UUID id : ids) {
            if (id == null) {
                continue;
            }
            defRepository.findById(Objects.requireNonNull(id, "categoryId")).ifPresent(def -> map.put(def.getId(), def));
            for (MetaCategoryDef ancestor : hierarchyRepository.findAncestorsByDescendant(id)) {
                if (ancestor != null && ancestor.getId() != null) {
                    map.putIfAbsent(ancestor.getId(), ancestor);
                }
            }
        }
        return map;
    }

    private void simulateTopologyItem(TopologyPlanItem item,
                                      Map<String, TopologyPlanItem> itemByOperationId,
                                      Map<UUID, UUID> effectiveParentMap,
                                      List<String> resolvedOrder,
                                      Map<UUID, UUID> finalParentMappings) {
        if (item.hasFailure()) {
            return;
        }

        for (String dependencyId : item.resolvedDependsOn) {
            TopologyPlanItem dependencyItem = itemByOperationId.get(dependencyId);
            if (dependencyItem != null && dependencyItem.hasFailure()) {
                item.failureCode = "CATEGORY_DEPENDENCY_UNSATISFIED";
                item.failureMessage = "dependency operation failed: operationId=" + dependencyId;
                return;
            }
        }

        item.effectiveSourceParentIdBefore = effectiveParentMap.get(item.operation.sourceNodeId);
        item.effectiveTargetParentId = item.operation.targetParentId;

        effectiveParentMap.put(item.operation.sourceNodeId, item.operation.targetParentId);
        finalParentMappings.put(item.operation.sourceNodeId, item.operation.targetParentId);
        resolvedOrder.add(item.operation.operationId);
    }

    private boolean resolveAtomicDefaultTrue(Boolean atomic) {
        return atomic == null || Boolean.TRUE.equals(atomic);
    }

    private boolean isEffectiveDescendant(UUID nodeId, UUID possibleAncestorId, Map<UUID, UUID> effectiveParentMap) {
        Set<UUID> visited = new HashSet<>();
        UUID currentId = nodeId;
        while (currentId != null && visited.add(currentId)) {
            UUID parentId = effectiveParentMap.get(currentId);
            if (Objects.equals(parentId, possibleAncestorId)) {
                return true;
            }
            currentId = parentId;
        }
        return false;
    }

    private MetaCategoryBatchTransferTopologyResponseDto buildTopologyResponse(TopologyPlan plan) {
        return buildTopologyResponse(plan, List.of(), null, null);
    }

    private MetaCategoryBatchTransferTopologyResponseDto buildTopologyResponse(TopologyPlan plan,
                                                                              List<TopologyPlanItem> rolledBackItems,
                                                                              TopologyPlanItem failedItem,
                                                                              RuntimeException failedException) {
        List<MetaCategoryBatchTransferTopologyItemResultDto> results = new ArrayList<>();
        for (TopologyPlanItem item : plan.items) {
            MetaCategoryBatchTransferTopologyItemResultDto result = new MetaCategoryBatchTransferTopologyItemResultDto();
            result.setOperationId(item.operation.operationId);
            result.setSourceNodeId(item.operation.sourceNodeId);
            result.setTargetParentId(item.operation.targetParentId);
            result.setEffectiveSourceParentIdBefore(item.effectiveSourceParentIdBefore);
            result.setEffectiveTargetParentId(item.effectiveTargetParentId);

            if (failedItem != null) {
                if (item == failedItem) {
                    result.setSuccess(Boolean.FALSE);
                    result.setCode(resolveBatchErrorCode(failedException));
                    result.setMessage(failedException == null ? "unknown error" : failedException.getMessage());
                } else if (rolledBackItems.contains(item)) {
                    result.setSuccess(Boolean.FALSE);
                    result.setCode(CODE_ATOMIC_ROLLBACK);
                    result.setMessage("atomic rollback triggered by failure in same batch");
                } else {
                    result.setSuccess(Boolean.FALSE);
                    result.setCode(CODE_ATOMIC_ABORTED);
                    result.setMessage("batch aborted due to atomic rollback");
                }
            } else if (item.hasFailure()) {
                result.setSuccess(Boolean.FALSE);
                result.setCode(item.failureCode);
                result.setMessage(item.failureMessage);
            } else {
                result.setSuccess(Boolean.TRUE);
            }
            results.add(result);
        }

        MetaCategoryBatchTransferTopologyResponseDto response = new MetaCategoryBatchTransferTopologyResponseDto();
        response.setTotal(results.size());
        response.setSuccessCount((int) results.stream().filter(result -> Boolean.TRUE.equals(result.getSuccess())).count());
        response.setFailureCount((int) results.stream().filter(result -> !Boolean.TRUE.equals(result.getSuccess())).count());
        response.setAtomic(plan.atomic);
        response.setDryRun(plan.dryRun);
        response.setPlanningMode(plan.planningMode);
        response.setResolvedOrder(plan.resolvedOrder);
        response.setPlanningWarnings(plan.planningWarnings);
        response.setFinalParentMappings(buildTopologyFinalParentMappings(plan));
        response.setResults(results);
        return response;
    }

    private List<MetaCategoryBatchTransferTopologyFinalParentMappingDto> buildTopologyFinalParentMappings(TopologyPlan plan) {
        List<MetaCategoryBatchTransferTopologyFinalParentMappingDto> mappings = new ArrayList<>();
        for (TopologyPlanItem item : plan.items) {
            if (item.hasFailure() || !plan.finalParentMappings.containsKey(item.operation.sourceNodeId)) {
                continue;
            }
            MetaCategoryBatchTransferTopologyFinalParentMappingDto mapping = new MetaCategoryBatchTransferTopologyFinalParentMappingDto();
            mapping.setSourceNodeId(item.operation.sourceNodeId);
            mapping.setFinalParentId(plan.finalParentMappings.get(item.operation.sourceNodeId));
            mapping.setDependsOnResolved(item.resolvedDependsOn.isEmpty() ? List.of() : new ArrayList<>(item.resolvedDependsOn));
            mappings.add(mapping);
        }
        return mappings;
    }

    private MetaCategoryBatchTransferResponseDto batchTransferAtomic(TransferPlan plan) {
        List<TransferPlanItem> failedItems = plan.items.stream().filter(TransferPlanItem::hasFailure).toList();
        if (!failedItems.isEmpty()) {
            List<MetaCategoryBatchTransferItemResultDto> results = new ArrayList<>();
            for (TransferPlanItem item : plan.items) {
                if (item.hasFailure()) {
                    results.add(buildTransferFailureResult(item, item.failureCode, item.failureMessage));
                } else {
                    results.add(buildTransferFailureResult(item, CODE_ATOMIC_ABORTED, "batch aborted due to atomic rollback"));
                }
            }
            return buildTransferResponse(plan, results);
        }

        List<TransferPlanItem> executedItems = new ArrayList<>();
        Map<Integer, TransferExecutionOutcome> outcomes = new HashMap<>();
        TransferPlanItem[] failedAt = new TransferPlanItem[1];
        RuntimeException[] failedException = new RuntimeException[1];

        try {
            requiresNewTxTemplate.executeWithoutResult(status -> {
                for (TransferPlanItem item : plan.items) {
                    if (item.normalized) {
                        continue;
                    }
                    try {
                        TransferExecutionOutcome outcome = executeTransfer(plan, item);
                        executedItems.add(item);
                        outcomes.put(item.operation.index, outcome);
                    } catch (RuntimeException ex) {
                        failedAt[0] = item;
                        failedException[0] = ex;
                        status.setRollbackOnly();
                        throw ex;
                    }
                }
            });
        } catch (RuntimeException ex) {
            List<MetaCategoryBatchTransferItemResultDto> results = new ArrayList<>();
            for (TransferPlanItem item : plan.items) {
                if (item == failedAt[0]) {
                    results.add(buildTransferFailureResult(item, resolveBatchErrorCode(failedException[0]), failedException[0].getMessage()));
                } else if (executedItems.contains(item)) {
                    results.add(buildTransferFailureResult(item, CODE_ATOMIC_ROLLBACK, "atomic rollback triggered by failure in same batch"));
                } else {
                    results.add(buildTransferFailureResult(item, CODE_ATOMIC_ABORTED, "batch aborted due to atomic rollback"));
                }
            }
            return buildTransferResponse(plan, results);
        }

        List<MetaCategoryBatchTransferItemResultDto> results = new ArrayList<>();
        for (TransferPlanItem item : plan.items) {
            if (item.normalized) {
                results.add(buildTransferNormalizedResult(item));
            } else {
                results.add(buildTransferSuccessResult(item, outcomes.get(item.operation.index)));
            }
        }
        return buildTransferResponse(plan, results);
    }

    private List<MetaCategoryBatchTransferItemResultDto> buildDryRunResults(TransferPlan plan) {
        List<MetaCategoryBatchTransferItemResultDto> results = new ArrayList<>();
        for (TransferPlanItem item : plan.items) {
            if (item.hasFailure()) {
                results.add(buildTransferFailureResult(item, item.failureCode, item.failureMessage));
            } else if (item.normalized) {
                results.add(buildTransferNormalizedResult(item));
            } else {
                MetaCategoryBatchTransferItemResultDto result = baseTransferResult(item);
                result.setSuccess(Boolean.TRUE);
                result.setAffectedNodeCount(item.affectedNodeCount);
                results.add(result);
            }
        }
        return results;
    }

    private TransferExecutionOutcome executeTransfer(TransferPlan plan, TransferPlanItem item) {
        return switch (plan.action) {
            case ACTION_MOVE -> executeMove(plan.businessDomain, item.operation.sourceNodeId, item.operation.targetParentId);
            case ACTION_COPY -> executeCopy(plan.businessDomain, item.operation.sourceNodeId, item.operation.targetParentId, plan.copyOptions, plan.operator);
            default -> throw new IllegalArgumentException("unsupported action: " + plan.action);
        };
    }

    private TransferExecutionOutcome executeMove(String businessDomain, UUID sourceId, UUID targetParentId) {
        MetaCategoryDef source = loadActiveCategory(sourceId, "CATEGORY_NOT_FOUND");
        MetaCategoryDef targetParent = targetParentId == null ? null : loadActiveCategory(targetParentId, "CATEGORY_TARGET_PARENT_NOT_FOUND");
        validateSourceAndTarget(source, targetParent, businessDomain);

        List<UUID> movedIds = hierarchyRepository.findDescendantIdsIncludingSelf(sourceId);
        if (movedIds.isEmpty()) {
            movedIds = List.of(sourceId);
        }

        MetaCategoryDef oldParent = source.getParent();
        source.setParent(targetParent);
        source.setSortOrder(nextSort(targetParentId));
        defRepository.save(source);
        applyParentMove(source, oldParent);

        return new TransferExecutionOutcome(
                movedIds.size(),
                movedIds,
                null,
                null,
                null,
                null,
                null
        );
    }

    private TransferExecutionOutcome executeCopy(String businessDomain,
                                                 UUID sourceId,
                                                 UUID targetParentId,
                                                 CopyOptionsResolved options,
                                                 String operator) {
        MetaCategoryDef source = loadActiveCategory(sourceId, "CATEGORY_NOT_FOUND");
        MetaCategoryDef targetParent = targetParentId == null ? null : loadActiveCategory(targetParentId, "CATEGORY_TARGET_PARENT_NOT_FOUND");
        validateSourceAndTarget(source, targetParent, businessDomain);

        List<MetaCategoryDef> subtree = new ArrayList<>();
        subtree.add(source);
        subtree.addAll(hierarchyRepository.findDescendantDefs(source.getId()));
        subtree = subtree.stream().distinct().sorted(CATEGORY_TREE_ORDER).toList();

        Map<UUID, MetaCategoryVersion> latestVersionByDefId = versionRepository.findByCategoryDefInAndIsLatestTrue(subtree).stream()
                .filter(version -> version.getCategoryDef() != null && version.getCategoryDef().getId() != null)
                .collect(Collectors.toMap(version -> version.getCategoryDef().getId(), version -> version, (left, right) -> left));

        Map<UUID, MetaCategoryDef> copiedBySourceId = new LinkedHashMap<>();
        List<UUID> createdIds = new ArrayList<>();
        List<MetaCategoryCopySourceMappingDto> sourceMappings = new ArrayList<>();
        List<MetaCategoryCodeMappingDto> codeMappings = new ArrayList<>();
        LinkedHashSet<UUID> parentIdsToNormalize = new LinkedHashSet<>();
        parentIdsToNormalize.add(targetParentId);

        for (MetaCategoryDef current : subtree) {
            MetaCategoryVersion currentVersion = latestVersionByDefId.get(current.getId());
            if (currentVersion == null) {
                throw new IllegalArgumentException("category has no latest version: id=" + current.getId());
            }

            MetaCategoryDef copiedParent = current.getId().equals(source.getId())
                    ? targetParent
                    : copiedBySourceId.get(current.getParent().getId());
            String copiedCode = generateCopyCode(businessDomain, current.getCodeKey());

            MetaCategoryDef copied = new MetaCategoryDef();
            copied.setBusinessDomain(businessDomain);
            copied.setCodeKey(copiedCode);
            copied.setStatus(options.defaultStatus);
            copied.setParent(copiedParent);
            copied.setSortOrder(current.getId().equals(source.getId()) ? nextSort(targetParentId) : current.getSortOrder());
            copied.setIsLeaf(Boolean.TRUE.equals(current.getIsLeaf()));
            copied.setExternalCode(current.getExternalCode());
            copied.setCopiedFromCategoryId(current.getId());
            copied.setCreatedBy(operator);
            defRepository.save(copied);

            MetaCategoryVersion copiedVersion = new MetaCategoryVersion();
            copiedVersion.setCategoryDef(copied);
            copiedVersion.setVersionNo(1);
            String copiedDisplayName = resolveCopyDisplayName(businessDomain, currentVersion, options);
            copiedVersion.setDisplayName(copiedDisplayName);
            copiedVersion.setRuleResolvedCodePrefix(currentVersion.getRuleResolvedCodePrefix());
            copiedVersion.setStructureJson(currentVersion.getStructureJson());
            copiedVersion.setHash(currentVersion.getHash());
            copiedVersion.setIsLatest(Boolean.TRUE);
            copiedVersion.setCreatedBy(operator);
            versionRepository.save(copiedVersion);

            applyCreateNodeMeta(copied, copiedParent, copiedVersion.getDisplayName());
            insertClosureForNewNode(copied, copiedParent);

            copiedBySourceId.put(current.getId(), copied);
            createdIds.add(copied.getId());
            parentIdsToNormalize.add(copied.getId());

            MetaCategoryCopySourceMappingDto sourceMapping = new MetaCategoryCopySourceMappingDto();
            sourceMapping.setSourceNodeId(current.getId());
            sourceMapping.setCreatedNodeId(copied.getId());
            sourceMapping.setCopiedFromCategoryId(current.getId());
            sourceMappings.add(sourceMapping);

            MetaCategoryCodeMappingDto codeMapping = new MetaCategoryCodeMappingDto();
            codeMapping.setSourceCode(current.getCodeKey());
            codeMapping.setCreatedCode(copiedCode);
            codeMappings.add(codeMapping);
        }

        refreshParentLeafIfNeeded(targetParent);
        for (UUID parentId : parentIdsToNormalize) {
            normalizeSiblingOrders(parentId);
        }

        MetaCategoryDef copiedRoot = copiedBySourceId.get(source.getId());
        return new TransferExecutionOutcome(
                createdIds.size(),
                null,
                copiedRoot == null ? null : copiedRoot.getId(),
                createdIds,
                source.getId(),
                sourceMappings,
                codeMappings
        );
    }

    private String resolveCopyDisplayName(String businessDomain, MetaCategoryVersion version, CopyOptionsResolved options) {
        String sourceDisplayName = trimToNull(version.getDisplayName());
        if (sourceDisplayName == null) {
            throw new IllegalArgumentException("category name is required");
        }
        return switch (options.namePolicy) {
            case "KEEP" -> sourceDisplayName;
            case "AUTO_SUFFIX" -> generateCopyDisplayName(businessDomain, sourceDisplayName);
            default -> throw new IllegalArgumentException("unsupported namePolicy: " + options.namePolicy);
        };
    }

    private MetaCategoryDef loadActiveCategory(UUID id, String notFoundCode) {
        UUID categoryId = Objects.requireNonNull(id, "id");
        MetaCategoryDef def = defRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryConflictException(notFoundCode, "category not found: id=" + id));
        if (isDeleted(def)) {
            throw new CategoryConflictException("CATEGORY_DELETED", "category is deleted: id=" + id);
        }
        return def;
    }

    private void validateSourceAndTarget(MetaCategoryDef source, MetaCategoryDef targetParent, String businessDomain) {
        if (!Objects.equals(source.getBusinessDomain(), businessDomain)) {
            throw new CategoryConflictException("CATEGORY_DOMAIN_MISMATCH", "source and request businessDomain mismatch: sourceId=" + source.getId());
        }
        if (targetParent != null) {
            if (!Objects.equals(targetParent.getBusinessDomain(), businessDomain)) {
                throw new CategoryConflictException("CATEGORY_DOMAIN_MISMATCH", "target parent and request businessDomain mismatch: targetParentId=" + targetParent.getId());
            }
            if (source.getId().equals(targetParent.getId())) {
                throw new CategoryConflictException("CATEGORY_TARGET_IS_SELF", "target parent cannot be self");
            }
            ensureNoCycle(source.getId(), targetParent.getId());
        }
    }

    private CopyOptionsResolved resolveCopyOptions(String action, MetaCategoryBatchCopyOptionsDto copyOptions) {
        if (!ACTION_COPY.equals(action)) {
            return null;
        }
        String versionPolicy = normalizeOption(copyOptions == null ? null : copyOptions.getVersionPolicy(), "CURRENT_ONLY");
        String codePolicy = normalizeOption(copyOptions == null ? null : copyOptions.getCodePolicy(), "AUTO_SUFFIX");
        String namePolicy = normalizeOption(copyOptions == null ? null : copyOptions.getNamePolicy(), "AUTO_SUFFIX");
        String defaultStatus = normalizeCopyDefaultStatus(copyOptions == null ? null : copyOptions.getDefaultStatus());

        if (!"CURRENT_ONLY".equals(versionPolicy)) {
            throw new IllegalArgumentException("unsupported versionPolicy: " + versionPolicy);
        }
        if (!"AUTO_SUFFIX".equals(codePolicy)) {
            throw new IllegalArgumentException("unsupported codePolicy: " + codePolicy);
        }
        if (!"KEEP".equals(namePolicy) && !"AUTO_SUFFIX".equals(namePolicy)) {
            throw new IllegalArgumentException("unsupported namePolicy: " + namePolicy);
        }
        return new CopyOptionsResolved(namePolicy, defaultStatus);
    }

    private String normalizeCopyDefaultStatus(String defaultStatus) {
        String normalized = trimToNull(defaultStatus);
        if (normalized == null) {
            return STATUS_DRAFT;
        }
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "DRAFT", "CREATED" -> STATUS_DRAFT;
            case "ACTIVE", "EFFECTIVE" -> STATUS_ACTIVE;
            case "INACTIVE", "INVALID" -> STATUS_INACTIVE;
            default -> throw new IllegalArgumentException("unsupported defaultStatus: " + defaultStatus);
        };
    }

    private String normalizeTransferAction(String action) {
        String normalized = normalizeOption(action, null);
        if (normalized == null) {
            throw new IllegalArgumentException("action is required");
        }
        if (!ACTION_MOVE.equals(normalized) && !ACTION_COPY.equals(normalized)) {
            throw new IllegalArgumentException("unsupported action: " + action);
        }
        return normalized;
    }

    private String normalizeTopologyPlanningMode(String planningMode) {
        String normalized = normalizeOption(planningMode, PLANNING_MODE_TOPOLOGY_AWARE);
        if (!PLANNING_MODE_TOPOLOGY_AWARE.equals(normalized)) {
            throw new IllegalArgumentException("unsupported planningMode: " + planningMode);
        }
        return normalized;
    }

    private String normalizeTopologyOrderingStrategy(String orderingStrategy) {
        String normalized = normalizeOption(orderingStrategy, ORDERING_STRATEGY_CLIENT_ORDER);
        if (!ORDERING_STRATEGY_CLIENT_ORDER.equals(normalized)
                && !ORDERING_STRATEGY_TOPOLOGICAL_BOTTOM_UP.equals(normalized)) {
            throw new IllegalArgumentException("unsupported orderingStrategy: " + orderingStrategy);
        }
        return normalized;
    }

    private String normalizeOption(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized == null ? defaultValue : normalized.toUpperCase(Locale.ROOT);
    }

    private List<TransferOperationContext> normalizeTransferOperations(List<MetaCategoryBatchTransferOperationDto> operations, UUID batchTargetParentId) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("operations is required");
        }
        if (operations.size() > BATCH_TRANSFER_MAX_SIZE) {
            throw new IllegalArgumentException("operations size must be <= " + BATCH_TRANSFER_MAX_SIZE);
        }

        List<TransferOperationContext> normalized = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            MetaCategoryBatchTransferOperationDto operation = operations.get(i);
            if (operation == null || operation.getSourceNodeId() == null) {
                throw new IllegalArgumentException("operations contains item with missing sourceNodeId");
            }
            normalized.add(new TransferOperationContext(
                    i,
                    trimToNull(operation.getClientOperationId()),
                    operation.getSourceNodeId(),
                    operation.getTargetParentId() == null ? batchTargetParentId : operation.getTargetParentId()
            ));
        }
        return normalized;
    }

    private List<TopologyOperationContext> normalizeTopologyOperations(List<MetaCategoryBatchTransferTopologyOperationDto> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("operations is required");
        }
        if (operations.size() > BATCH_TRANSFER_MAX_SIZE) {
            throw new IllegalArgumentException("operations size must be <= " + BATCH_TRANSFER_MAX_SIZE);
        }

        LinkedHashSet<String> operationIds = new LinkedHashSet<>();
        LinkedHashSet<UUID> sourceIds = new LinkedHashSet<>();
        List<TopologyOperationContext> normalized = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            MetaCategoryBatchTransferTopologyOperationDto operation = operations.get(i);
            if (operation == null || operation.getSourceNodeId() == null) {
                throw new IllegalArgumentException("operations contains item with missing sourceNodeId");
            }
            String operationId = trimToNull(operation.getOperationId());
            if (operationId == null) {
                throw new IllegalArgumentException("operationId is required");
            }
            if (!operationIds.add(operationId)) {
                throw new IllegalArgumentException("duplicate operationId: " + operationId);
            }
            if (!sourceIds.add(operation.getSourceNodeId())) {
                throw new IllegalArgumentException("duplicate sourceNodeId: " + operation.getSourceNodeId());
            }
            List<String> dependsOn = operation.getDependsOnOperationIds() == null ? List.of() : new ArrayList<>(operation.getDependsOnOperationIds());
            normalized.add(new TopologyOperationContext(
                    i,
                    operationId,
                    operation.getSourceNodeId(),
                    operation.getTargetParentId(),
                    dependsOn,
                    Boolean.TRUE.equals(operation.getAllowDescendantFirstSplit()),
                    operation.getExpectedSourceParentId()
            ));
        }
        return normalized;
    }

    private Map<UUID, MetaCategoryDef> loadCategoryMap(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MetaCategoryDef> map = new HashMap<>();
        for (MetaCategoryDef def : defRepository.findAllById(ids)) {
            map.put(def.getId(), def);
        }
        return map;
    }

    private Map<UUID, Set<UUID>> loadDescendantMap(Set<UUID> sourceIds) {
        Map<UUID, Set<UUID>> descendantMap = new HashMap<>();
        for (UUID sourceId : sourceIds) {
            descendantMap.put(sourceId, new LinkedHashSet<>(hierarchyRepository.findDescendantIdsIncludingSelf(sourceId)));
        }
        return descendantMap;
    }

    private void validatePlanItem(TransferPlanItem item,
                                  MetaCategoryDef source,
                                  MetaCategoryDef target,
                                  String businessDomain,
                                  Map<UUID, Set<UUID>> descendantMap) {
        if (source == null) {
            item.failureCode = "CATEGORY_NOT_FOUND";
            item.failureMessage = "source category not found: id=" + item.operation.sourceNodeId;
            return;
        }
        if (isDeleted(source)) {
            item.failureCode = "CATEGORY_DELETED";
            item.failureMessage = "source category is deleted: id=" + source.getId();
            return;
        }
        if (!Objects.equals(source.getBusinessDomain(), businessDomain)) {
            item.failureCode = "CATEGORY_DOMAIN_MISMATCH";
            item.failureMessage = "source and request businessDomain mismatch: sourceId=" + source.getId();
            return;
        }
        if (item.operation.targetParentId == null) {
            return;
        }
        if (target == null) {
            item.failureCode = "CATEGORY_TARGET_PARENT_NOT_FOUND";
            item.failureMessage = "target parent not found: id=" + item.operation.targetParentId;
            return;
        }
        if (isDeleted(target)) {
            item.failureCode = "CATEGORY_DELETED";
            item.failureMessage = "target parent is deleted: id=" + target.getId();
            return;
        }
        if (!Objects.equals(target.getBusinessDomain(), businessDomain)) {
            item.failureCode = "CATEGORY_DOMAIN_MISMATCH";
            item.failureMessage = "target parent and request businessDomain mismatch: targetParentId=" + target.getId();
            return;
        }
        if (source.getId().equals(target.getId())) {
            item.failureCode = "CATEGORY_TARGET_IS_SELF";
            item.failureMessage = "target parent cannot be self";
            return;
        }
        if (descendantMap.getOrDefault(source.getId(), Set.of()).contains(target.getId())) {
            item.failureCode = "CATEGORY_TARGET_IN_DESCENDANT";
            item.failureMessage = "target parent is inside source subtree";
        }
    }

    private void applySourceOverlapRules(List<TransferPlanItem> items, Map<UUID, Set<UUID>> descendantMap) {
        List<TransferPlanItem> candidates = items.stream()
                .filter(item -> !item.hasFailure())
                .sorted(Comparator.comparingInt(this::safeAffectedNodeCount))
                .toList();

        for (TransferPlanItem item : candidates) {
            if (item.hasFailure()) {
                continue;
            }
            TransferPlanItem nearestAncestor = null;
            for (TransferPlanItem other : candidates) {
                if (item == other || other.hasFailure()) {
                    continue;
                }
                Set<UUID> descendants = descendantMap.getOrDefault(other.operation.sourceNodeId, Set.of());
                if (!descendants.contains(item.operation.sourceNodeId) || other.operation.sourceNodeId.equals(item.operation.sourceNodeId)) {
                    continue;
                }
                if (nearestAncestor == null || safeAffectedNodeCount(nearestAncestor) > safeAffectedNodeCount(other)) {
                    nearestAncestor = other;
                }
            }
            if (nearestAncestor == null) {
                continue;
            }
            if (Objects.equals(nearestAncestor.operation.targetParentId, item.operation.targetParentId)) {
                item.normalized = true;
                item.normalizedSourceNodeId = nearestAncestor.operation.sourceNodeId;
                item.affectedNodeCount = 0;
                item.warnings = List.of("normalized by ancestor operation");
            } else {
                item.failureCode = "CATEGORY_SOURCE_OVERLAP_TARGET_CONFLICT";
                item.failureMessage = "ancestor and descendant operations target different parents";
            }
        }
    }

    private int safeAffectedNodeCount(TransferPlanItem item) {
        if (item == null || item.affectedNodeCount == null) {
            return Integer.MAX_VALUE;
        }
        return item.affectedNodeCount;
    }

    private MetaCategoryBatchTransferItemResultDto buildTransferSuccessResult(TransferPlanItem item, TransferExecutionOutcome outcome) {
        MetaCategoryBatchTransferItemResultDto result = baseTransferResult(item);
        result.setSuccess(Boolean.TRUE);
        result.setAffectedNodeCount(outcome.affectedNodeCount);
        result.setMovedIds(outcome.movedIds);
        result.setCreatedRootId(outcome.createdRootId);
        result.setCreatedIds(outcome.createdIds);
        result.setCopiedFromCategoryId(outcome.copiedFromCategoryId);
        result.setSourceMappings(outcome.sourceMappings);
        result.setCodeMappings(outcome.codeMappings);
        return result;
    }

    private MetaCategoryBatchTransferItemResultDto buildTransferNormalizedResult(TransferPlanItem item) {
        MetaCategoryBatchTransferItemResultDto result = baseTransferResult(item);
        result.setSuccess(Boolean.TRUE);
        result.setAffectedNodeCount(0);
        result.setCode(CODE_SOURCE_OVERLAP_NORMALIZED);
        result.setMessage("source node skipped because ancestor already covers subtree");
        result.setWarning(item.warnings);
        return result;
    }

    private MetaCategoryBatchTransferItemResultDto buildTransferFailureResult(TransferPlanItem item, String code, String message) {
        MetaCategoryBatchTransferItemResultDto result = baseTransferResult(item);
        result.setSuccess(Boolean.FALSE);
        result.setAffectedNodeCount(0);
        result.setCode(code);
        result.setMessage(message == null ? "unknown error" : message);
        if (!item.warnings.isEmpty()) {
            result.setWarning(item.warnings);
        }
        return result;
    }

    private MetaCategoryBatchTransferItemResultDto baseTransferResult(TransferPlanItem item) {
        MetaCategoryBatchTransferItemResultDto result = new MetaCategoryBatchTransferItemResultDto();
        result.setClientOperationId(item.operation.clientOperationId);
        result.setSourceNodeId(item.operation.sourceNodeId);
        result.setNormalizedSourceNodeId(item.normalizedSourceNodeId);
        result.setTargetParentId(item.operation.targetParentId);
        result.setAction(item.action);
        return result;
    }

    private MetaCategoryBatchTransferResponseDto buildTransferResponse(TransferPlan plan,
                                                                      List<MetaCategoryBatchTransferItemResultDto> results) {
        MetaCategoryBatchTransferResponseDto response = new MetaCategoryBatchTransferResponseDto();
        response.setTotal(results.size());
        response.setSuccessCount((int) results.stream().filter(result -> Boolean.TRUE.equals(result.getSuccess())).count());
        response.setFailureCount((int) results.stream().filter(result -> !Boolean.TRUE.equals(result.getSuccess())).count());
        response.setNormalizedCount((int) results.stream().filter(result -> CODE_SOURCE_OVERLAP_NORMALIZED.equals(result.getCode())).count());
        response.setMovedCount(results.stream()
                .filter(result -> ACTION_MOVE.equals(result.getAction()) && Boolean.TRUE.equals(result.getSuccess()))
            .mapToInt(result -> result.getAffectedNodeCount() == null ? 0 : result.getAffectedNodeCount())
            .sum());
        response.setCopiedCount(results.stream()
                .filter(result -> ACTION_COPY.equals(result.getAction()) && Boolean.TRUE.equals(result.getSuccess()))
            .mapToInt(result -> result.getAffectedNodeCount() == null ? 0 : result.getAffectedNodeCount())
            .sum());
        response.setAtomic(plan.atomic);
        response.setDryRun(plan.dryRun);
        response.setWarnings(plan.warnings);
        response.setResults(results);
        return response;
    }

    private String generateCopyCode(String businessDomain, String sourceCode) {
        String baseCode = requireCode(sourceCode);
        String copyPrefix = baseCode + "-COPY-";
        Set<String> existingCopyCodes = new HashSet<>(
                defRepository.findCodeKeysByBusinessDomainAndCodeKeyPrefix(businessDomain, copyPrefix)
        );
        for (int i = 1; i <= 999; i++) {
            String candidate = baseCode + String.format(Locale.ROOT, "-COPY-%03d", i);
            if (!existingCopyCodes.contains(candidate)) {
                return candidate;
            }
        }
        throw new CategoryConflictException("CATEGORY_CODE_CONFLICT", "unable to allocate copy code for source code=" + sourceCode);
    }

    private String generateCopyDisplayName(String businessDomain, String sourceDisplayName) {
        String baseName = trimToNull(sourceDisplayName);
        if (baseName == null) {
            throw new IllegalArgumentException("category name is required");
        }
        if (!versionRepository.existsLatestByBusinessDomainAndDisplayName(businessDomain, baseName)) {
            return baseName;
        }
        for (int i = 1; i <= 999; i++) {
            String candidate = baseName + String.format(Locale.ROOT, "-COPY-%03d", i);
            if (!versionRepository.existsLatestByBusinessDomainAndDisplayName(businessDomain, candidate)) {
                return candidate;
            }
        }
        throw new CategoryConflictException("CATEGORY_NAME_CONFLICT", "unable to allocate copy name for source name=" + sourceDisplayName);
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
        UUID categoryId = Objects.requireNonNull(id, "id");
        return defRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException("category not found: id=" + id));
    }

    private MetaCategoryBatchDeleteResponseDto batchDeleteDryRun(List<UUID> ids, boolean cascade, boolean confirm) {
        List<MetaCategoryBatchDeleteItemResultDto> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        int totalWouldDeleteCount = 0;

        for (UUID id : ids) {
            try {
                int wouldDeleteCount = estimateDeleteCount(id, cascade, confirm);
                MetaCategoryBatchDeleteItemResultDto item = new MetaCategoryBatchDeleteItemResultDto();
                item.setId(id);
                item.setSuccess(Boolean.TRUE);
                item.setWouldDeleteCount(wouldDeleteCount);
                if (wouldDeleteCount == 0) {
                    item.setCode(CODE_ALREADY_DELETED);
                    item.setMessage("category is already deleted");
                }
                results.add(item);
                successCount++;
                totalWouldDeleteCount += wouldDeleteCount;
            } catch (RuntimeException ex) {
                results.add(buildFailureItem(id, ex));
                failureCount++;
            }
        }

        MetaCategoryBatchDeleteResponseDto response = new MetaCategoryBatchDeleteResponseDto();
        response.setTotal(ids.size());
        response.setSuccessCount(successCount);
        response.setFailureCount(failureCount);
        response.setDeletedCount(0);
        response.setTotalWouldDeleteCount(totalWouldDeleteCount);
        response.setAtomic(Boolean.FALSE);
        response.setDryRun(Boolean.TRUE);
        response.setResults(results);
        return response;
    }

    private MetaCategoryBatchDeleteResponseDto batchDeleteNonAtomic(List<UUID> ids, boolean cascade, boolean confirm, String operator) {
        List<MetaCategoryBatchDeleteItemResultDto> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        int totalDeletedCount = 0;

        for (UUID id : ids) {
            try {
                Integer deletedCount = requiresNewTxTemplate.execute(status -> delete(id, cascade, confirm, operator));
                int affected = deletedCount == null ? 0 : deletedCount;
                MetaCategoryBatchDeleteItemResultDto item = new MetaCategoryBatchDeleteItemResultDto();
                item.setId(id);
                item.setSuccess(Boolean.TRUE);
                item.setDeletedCount(affected);
                if (affected == 0) {
                    item.setCode(CODE_ALREADY_DELETED);
                    item.setMessage("category is already deleted");
                }
                results.add(item);
                successCount++;
                totalDeletedCount += affected;
            } catch (RuntimeException ex) {
                results.add(buildFailureItem(id, ex));
                failureCount++;
            }
        }

        MetaCategoryBatchDeleteResponseDto response = new MetaCategoryBatchDeleteResponseDto();
        response.setTotal(ids.size());
        response.setSuccessCount(successCount);
        response.setFailureCount(failureCount);
        response.setDeletedCount(totalDeletedCount);
        response.setTotalWouldDeleteCount(0);
        response.setAtomic(Boolean.FALSE);
        response.setDryRun(Boolean.FALSE);
        response.setResults(results);
        return response;
    }

    private MetaCategoryBatchDeleteResponseDto batchDeleteAtomic(List<UUID> ids, boolean cascade, boolean confirm, String operator) {
        List<UUID> committedIds = new ArrayList<>();
        Map<UUID, Integer> deletedCountMap = new HashMap<>();
        UUID[] failedId = new UUID[1];
        RuntimeException[] failedException = new RuntimeException[1];

        try {
            requiresNewTxTemplate.executeWithoutResult(status -> {
                for (UUID id : ids) {
                    try {
                        int affected = delete(id, cascade, confirm, operator);
                        committedIds.add(id);
                        deletedCountMap.put(id, affected);
                    } catch (RuntimeException ex) {
                        failedId[0] = id;
                        failedException[0] = ex;
                        status.setRollbackOnly();
                        throw ex;
                    }
                }
            });
        } catch (RuntimeException ex) {
            List<MetaCategoryBatchDeleteItemResultDto> results = new ArrayList<>();
            for (UUID id : ids) {
                if (failedId[0] != null && failedId[0].equals(id)) {
                    results.add(buildFailureItem(id, failedException[0] == null ? ex : failedException[0]));
                    continue;
                }

                MetaCategoryBatchDeleteItemResultDto item = new MetaCategoryBatchDeleteItemResultDto();
                item.setId(id);
                item.setSuccess(Boolean.FALSE);
                item.setDeletedCount(0);
                if (committedIds.contains(id)) {
                    item.setCode(CODE_ATOMIC_ROLLBACK);
                    item.setMessage("atomic rollback triggered by failure in same batch");
                } else {
                    item.setCode(CODE_ATOMIC_ABORTED);
                    item.setMessage("batch aborted due to atomic rollback");
                }
                results.add(item);
            }

            MetaCategoryBatchDeleteResponseDto response = new MetaCategoryBatchDeleteResponseDto();
            response.setTotal(ids.size());
            response.setSuccessCount(0);
            response.setFailureCount(ids.size());
            response.setDeletedCount(0);
            response.setTotalWouldDeleteCount(0);
            response.setAtomic(Boolean.TRUE);
            response.setDryRun(Boolean.FALSE);
            response.setResults(results);
            return response;
        }

        List<MetaCategoryBatchDeleteItemResultDto> results = new ArrayList<>();
        int totalDeletedCount = 0;
        for (UUID id : ids) {
            int affected = deletedCountMap.getOrDefault(id, 0);
            MetaCategoryBatchDeleteItemResultDto item = new MetaCategoryBatchDeleteItemResultDto();
            item.setId(id);
            item.setSuccess(Boolean.TRUE);
            item.setDeletedCount(affected);
            if (affected == 0) {
                item.setCode(CODE_ALREADY_DELETED);
                item.setMessage("category is already deleted");
            }
            totalDeletedCount += affected;
            results.add(item);
        }

        MetaCategoryBatchDeleteResponseDto response = new MetaCategoryBatchDeleteResponseDto();
        response.setTotal(ids.size());
        response.setSuccessCount(ids.size());
        response.setFailureCount(0);
        response.setDeletedCount(totalDeletedCount);
        response.setTotalWouldDeleteCount(0);
        response.setAtomic(Boolean.TRUE);
        response.setDryRun(Boolean.FALSE);
        response.setResults(results);
        return response;
    }

    private int estimateDeleteCount(UUID id, boolean cascade, boolean confirm) {
        MetaCategoryDef def = loadExisting(id);
        if (isDeleted(def)) {
            return 0;
        }

        long directChildren = hierarchyRepository.countDirectChildren(id);
        if (directChildren > 0 && (!cascade || !confirm)) {
            throw new CategoryConflictException(
                    "CATEGORY_HAS_CHILDREN",
                    "category has children, please confirm cascade deletion with cascade=true&confirm=true"
            );
        }

        if (!cascade) {
            return 1;
        }

        List<UUID> descendantIds = hierarchyRepository.findDescendantIdsIncludingSelf(id);
        if (descendantIds.isEmpty()) {
            descendantIds = List.of(id);
        }
        List<MetaCategoryDef> targets = defRepository.findAllById(Objects.requireNonNull(descendantIds, "descendantIds"));
        int count = 0;
        for (MetaCategoryDef target : targets) {
            if (!isDeleted(target)) {
                count++;
            }
        }
        return count;
    }

    private List<UUID> normalizeBatchIds(List<UUID> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            throw new IllegalArgumentException("ids is required");
        }
        if (rawIds.size() > BATCH_DELETE_MAX_SIZE) {
            throw new IllegalArgumentException("ids size must be <= " + BATCH_DELETE_MAX_SIZE);
        }

        LinkedHashSet<UUID> deDuplicated = new LinkedHashSet<>();
        for (UUID id : rawIds) {
            if (id == null) {
                throw new IllegalArgumentException("ids contains null item");
            }
            deDuplicated.add(id);
        }

        if (deDuplicated.isEmpty()) {
            throw new IllegalArgumentException("ids is required");
        }
        return new ArrayList<>(deDuplicated);
    }

    private MetaCategoryBatchDeleteItemResultDto buildFailureItem(UUID id, RuntimeException ex) {
        MetaCategoryBatchDeleteItemResultDto item = new MetaCategoryBatchDeleteItemResultDto();
        item.setId(id);
        item.setSuccess(Boolean.FALSE);
        item.setDeletedCount(0);
        item.setCode(resolveBatchErrorCode(ex));
        item.setMessage(ex == null ? "unknown error" : ex.getMessage());
        return item;
    }

    private String resolveBatchErrorCode(RuntimeException ex) {
        if (ex instanceof CategoryConflictException conflict) {
            return conflict.getCode();
        }
        if (ex instanceof CategoryNotFoundException) {
            return "CATEGORY_NOT_FOUND";
        }
        if (ex instanceof IllegalArgumentException) {
            return "INVALID_ARGUMENT";
        }
        return "INTERNAL_ERROR";
    }

    private void ensureParentActive(MetaCategoryDef parent) {
        if (parent == null || parent.getStatus() == null) {
            return;
        }
        if (STATUS_DELETED.equalsIgnoreCase(parent.getStatus().trim())) {
            throw new IllegalArgumentException("parent category is deleted: id=" + parent.getId());
        }
    }

    private MetaCategoryDef resolveCreateParent(UUID parentId, String businessDomain) {
        if (parentId == null) {
            return null;
        }
        MetaCategoryDef parent = loadExisting(parentId);
        ensureParentActive(parent);
        ensureParentBusinessDomain(parent, businessDomain);
        return parent;
    }

    private void ensureParentBusinessDomain(MetaCategoryDef parent, String businessDomain) {
        if (parent == null) {
            return;
        }
        if (!Objects.equals(parent.getBusinessDomain(), businessDomain)) {
            throw new CategoryConflictException(
                    "CATEGORY_DOMAIN_MISMATCH",
                    "parent category and request businessDomain mismatch: parentId=" + parent.getId()
            );
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

    private Integer resolveLevel(String path, Short depth) {
        Integer pathLevel = levelFromPath(path);
        if (pathLevel != null) {
            return pathLevel;
        }
        return depthToLevel(depth, ROOT_DEPTH_BASE);
    }

    private Integer levelFromPath(String path) {
        String normalized = trimToNull(path);
        if (normalized == null) {
            return null;
        }
        int segments = 0;
        for (String segment : normalized.split("/")) {
            if (!segment.isBlank()) {
                segments++;
            }
        }
        return segments == 0 ? null : segments;
    }

    private Integer depthToLevel(Short depth, short rootDepthBase) {
        if (depth == null) {
            return null;
        }
        int level = depth - rootDepthBase + 1;
        return Math.max(level, 1);
    }

    private String coalesceTrim(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private MetaCodeRuleService.GeneratedCodeResult resolveCategoryCodeForCreate(CreateCategoryRequestDto req,
                                                                                 String businessDomain,
                                                                                 MetaCategoryDef parent,
                                                                                 UUID targetId,
                                                                                 String operator) {
        String explicitMode = trimToNull(req.getGenerationMode());
        String generationMode = explicitMode == null
                ? (isBlank(req.getCode()) ? "AUTO" : "MANUAL")
                : explicitMode.trim().toUpperCase(Locale.ROOT);
        boolean freezeCode = Boolean.TRUE.equals(req.getFreezeCode());
        LinkedHashMap<String, String> context = buildCategoryCodeContext(businessDomain, parent);

        return switch (generationMode) {
            case "AUTO" -> {
                if (!isBlank(req.getCode())) {
                    throw new IllegalArgumentException("code must be empty when generationMode=AUTO");
                }
                String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(businessDomain);
                yield metaCodeRuleService.generateCode(
                        ruleCode,
                        "CATEGORY",
                        targetId,
                        context,
                        null,
                        operator,
                        freezeCode
                );
                    }
                    case "AUTO_RESERVED" -> {
                    String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(businessDomain);
                    Integer ruleVersion = metaCodeRuleService.detail(ruleCode).getLatestVersionNo();
                    yield new MetaCodeRuleService.GeneratedCodeResult(
                        requireCode(req.getCode()),
                        ruleCode,
                        ruleVersion,
                        false,
                        freezeCode
                    );
                    }
            case "MANUAL" -> {
                String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(businessDomain);
                yield metaCodeRuleService.generateCode(
                        ruleCode,
                        "CATEGORY",
                        targetId,
                        context,
                        requireCode(req.getCode()),
                        operator,
                        freezeCode
                );
            }
            default -> throw new IllegalArgumentException("unsupported generationMode: " + generationMode);
        };
    }

    private LinkedHashMap<String, String> buildCategoryCodeContext(String businessDomain, MetaCategoryDef parent) {
        LinkedHashMap<String, String> context = new LinkedHashMap<>();
        context.put("BUSINESS_DOMAIN", businessDomain);
        if (parent != null && !isBlank(parent.getCodeKey())) {
            context.put("PARENT_CODE", parent.getCodeKey());
        }
        return context;
    }

    private int normalizePreviewCount(Integer count) {
        if (count == null) {
            return 1;
        }
        return Math.max(1, Math.min(count, 5));
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
