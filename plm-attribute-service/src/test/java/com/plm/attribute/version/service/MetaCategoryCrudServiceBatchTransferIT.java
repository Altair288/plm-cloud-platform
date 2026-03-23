package com.plm.attribute.version.service;

import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferItemResultDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferOperationDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferRequestDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferResponseDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import com.plm.common.version.domain.CategoryHierarchy;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
class MetaCategoryCrudServiceBatchTransferIT {

    private static final short TEST_ROOT_DEPTH = 1;

    private static final class TransferFixture {
        private UUID sourceRootId;
        private UUID sourceChildId;
        private UUID targetParentId;
        private UUID otherRootId;
    }

    @Autowired
    private MetaCategoryCrudService crudService;

    @Autowired
    private MetaCategoryDefRepository defRepository;

    @Autowired
    private MetaCategoryVersionRepository versionRepository;

    @Autowired
    private CategoryHierarchyRepository hierarchyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanupCommittedTestData() {
        inNewTransaction(() -> {
            List<MetaCategoryDef> testDefs = defRepository.findAll().stream()
                    .filter(this::isCommittedTestDef)
                    .toList();
            if (testDefs.isEmpty()) {
                return null;
            }

            Set<UUID> testIds = testDefs.stream().map(MetaCategoryDef::getId).collect(java.util.stream.Collectors.toSet());
            List<CategoryHierarchy> hierarchies = hierarchyRepository.findAll().stream()
                    .filter(row -> row.getAncestorDef() != null && row.getDescendantDef() != null)
                    .filter(row -> testIds.contains(row.getAncestorDef().getId()) || testIds.contains(row.getDescendantDef().getId()))
                    .toList();
            if (!hierarchies.isEmpty()) {
                hierarchyRepository.deleteAll(hierarchies);
            }

            List<MetaCategoryVersion> versions = versionRepository.findAll().stream()
                    .filter(version -> version.getCategoryDef() != null && testIds.contains(version.getCategoryDef().getId()))
                    .toList();
            if (!versions.isEmpty()) {
                versionRepository.deleteAll(versions);
            }

            defRepository.deleteAll(testDefs);
            return null;
        });
    }

    @Test
    void batchTransfer_copy_shouldCreateSubtreeAndRecordCopiedFrom() {
        TransferFixture fixture = inNewTransaction(() -> {
            TransferFixture created = new TransferFixture();
            MetaCategoryDef sourceRoot = createNode("MATERIAL", uniqueCode("IT-COPY-SRC-ROOT"), null, "active", 1, TEST_ROOT_DEPTH, false);
            MetaCategoryDef sourceChild = createNode("MATERIAL", uniqueCode("IT-COPY-SRC-CHILD"), sourceRoot, "active", 1, (short) (TEST_ROOT_DEPTH + 1), true);
            MetaCategoryDef targetParent = createNode("MATERIAL", uniqueCode("IT-COPY-TARGET"), null, "active", 2, TEST_ROOT_DEPTH, true);
            saveClosureRows(List.of(sourceRoot, sourceChild, targetParent));
            created.sourceRootId = sourceRoot.getId();
            created.sourceChildId = sourceChild.getId();
            created.targetParentId = targetParent.getId();
            return created;
        });

        MetaCategoryBatchTransferRequestDto request = new MetaCategoryBatchTransferRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("COPY");
        request.setAtomic(false);
        request.setDryRun(false);
        request.setOperator("it-transfer");
        request.setTargetParentId(fixture.targetParentId);
        request.setOperations(List.of(operation("OP-COPY-1", fixture.sourceRootId, null)));

        MetaCategoryBatchTransferResponseDto response = crudService.batchTransfer(request);

        Assertions.assertEquals(1, response.getTotal());
        Assertions.assertEquals(1, response.getSuccessCount());
        Assertions.assertEquals(2, response.getCopiedCount());

        MetaCategoryBatchTransferItemResultDto result = response.getResults().get(0);
        Assertions.assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        Assertions.assertNotNull(result.getCreatedRootId());
        Assertions.assertEquals(2, result.getCreatedIds().size());
        Assertions.assertEquals(2, result.getSourceMappings().size());

        MetaCategoryDetailDto copiedRoot = crudService.detail(result.getCreatedRootId());
        Assertions.assertEquals(fixture.sourceRootId, copiedRoot.getCopiedFromCategoryId());
        Assertions.assertEquals(fixture.targetParentId, copiedRoot.getParentId());

        List<MetaCategoryDef> createdDefs = defRepository.findAllById(result.getCreatedIds());
        Assertions.assertEquals(2, createdDefs.size());
        Assertions.assertTrue(createdDefs.stream().allMatch(def -> "draft".equals(def.getStatus())));
        Assertions.assertTrue(createdDefs.stream().allMatch(def -> def.getCopiedFromCategoryId() != null));
    }

    @Test
    void batchTransfer_copy_shouldAllocateFirstAvailableSuffixFromPrefetchedCodes() {
        String sourceCode = "IT-COPY-CODE-BASE";
        TransferFixture fixture = inNewTransaction(() -> {
            TransferFixture created = new TransferFixture();
            MetaCategoryDef sourceRoot = createNode("MATERIAL", sourceCode, null, "active", 1, TEST_ROOT_DEPTH, true);
            MetaCategoryDef targetParent = createNode("MATERIAL", uniqueCode("IT-COPY-CODE-TARGET"), null, "active", 2, TEST_ROOT_DEPTH, true);
            MetaCategoryDef existingCopy001 = createNode("MATERIAL", sourceCode + "-COPY-001", null, "active", 3, TEST_ROOT_DEPTH, true);
            MetaCategoryDef existingCopy003 = createNode("MATERIAL", sourceCode + "-COPY-003", null, "active", 4, TEST_ROOT_DEPTH, true);
            saveClosureRows(List.of(sourceRoot, targetParent, existingCopy001, existingCopy003));
            created.sourceRootId = sourceRoot.getId();
            created.targetParentId = targetParent.getId();
            return created;
        });

        MetaCategoryBatchTransferRequestDto request = new MetaCategoryBatchTransferRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("COPY");
        request.setAtomic(false);
        request.setDryRun(false);
        request.setOperator("it-transfer");
        request.setTargetParentId(fixture.targetParentId);
        request.setOperations(List.of(operation("OP-COPY-CODE", fixture.sourceRootId, null)));

        MetaCategoryBatchTransferResponseDto response = crudService.batchTransfer(request);

        Assertions.assertEquals(1, response.getSuccessCount());
        MetaCategoryBatchTransferItemResultDto result = response.getResults().get(0);
        Assertions.assertNotNull(result.getCreatedRootId());

        MetaCategoryDef copiedRoot = inNewTransaction(() -> defRepository.findById(result.getCreatedRootId()).orElseThrow());
        Assertions.assertEquals(sourceCode + "-COPY-002", copiedRoot.getCodeKey());
    }

    @Test
    void batchTransfer_move_shouldNormalizeDescendantOperationWhenTargetSame() {
        TransferFixture fixture = inNewTransaction(() -> {
            TransferFixture created = new TransferFixture();
            MetaCategoryDef sourceRoot = createNode("MATERIAL", uniqueCode("IT-MOVE-SRC-ROOT"), null, "active", 1, TEST_ROOT_DEPTH, false);
            MetaCategoryDef sourceChild = createNode("MATERIAL", uniqueCode("IT-MOVE-SRC-CHILD"), sourceRoot, "active", 1, (short) (TEST_ROOT_DEPTH + 1), true);
            MetaCategoryDef targetParent = createNode("MATERIAL", uniqueCode("IT-MOVE-TARGET"), null, "active", 2, TEST_ROOT_DEPTH, true);
            saveClosureRows(List.of(sourceRoot, sourceChild, targetParent));
            created.sourceRootId = sourceRoot.getId();
            created.sourceChildId = sourceChild.getId();
            created.targetParentId = targetParent.getId();
            return created;
        });

        MetaCategoryBatchTransferRequestDto request = new MetaCategoryBatchTransferRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setAtomic(false);
        request.setDryRun(false);
        request.setOperator("it-transfer");
        request.setTargetParentId(fixture.targetParentId);
        request.setOperations(List.of(
            operation("OP-MOVE-ROOT", fixture.sourceRootId, null),
            operation("OP-MOVE-CHILD", fixture.sourceChildId, null)
        ));

        MetaCategoryBatchTransferResponseDto response = crudService.batchTransfer(request);

        Assertions.assertEquals(2, response.getTotal());
        Assertions.assertEquals(2, response.getSuccessCount());
        Assertions.assertEquals(1, response.getNormalizedCount());
        Assertions.assertEquals(2, response.getMovedCount());

        MetaCategoryBatchTransferItemResultDto normalized = response.getResults().get(1);
        Assertions.assertEquals("SOURCE_OVERLAP_NORMALIZED", normalized.getCode());

        MetaCategoryDef movedRoot = inNewTransaction(() -> defRepository.findById(fixture.sourceRootId).orElseThrow());
        MetaCategoryDef movedChild = inNewTransaction(() -> defRepository.findById(fixture.sourceChildId).orElseThrow());
        Assertions.assertEquals(fixture.targetParentId, movedRoot.getParent().getId());
        Assertions.assertEquals(fixture.sourceRootId, movedChild.getParent().getId());
    }

    @Test
    void batchTransfer_atomic_shouldAbortRemainingItemsWhenOneOperationConflicts() {
        TransferFixture fixture = inNewTransaction(() -> {
            TransferFixture created = new TransferFixture();
            MetaCategoryDef sourceRoot = createNode("MATERIAL", uniqueCode("IT-ATOMIC-SRC-ROOT"), null, "active", 1, TEST_ROOT_DEPTH, false);
            MetaCategoryDef sourceChild = createNode("MATERIAL", uniqueCode("IT-ATOMIC-SRC-CHILD"), sourceRoot, "active", 1, (short) (TEST_ROOT_DEPTH + 1), true);
            MetaCategoryDef otherRoot = createNode("MATERIAL", uniqueCode("IT-ATOMIC-OTHER"), null, "active", 2, TEST_ROOT_DEPTH, true);
            MetaCategoryDef targetParent = createNode("MATERIAL", uniqueCode("IT-ATOMIC-TARGET"), null, "active", 3, TEST_ROOT_DEPTH, true);
            saveClosureRows(List.of(sourceRoot, sourceChild, otherRoot, targetParent));
            created.sourceRootId = sourceRoot.getId();
            created.sourceChildId = sourceChild.getId();
            created.otherRootId = otherRoot.getId();
            created.targetParentId = targetParent.getId();
            return created;
        });

        MetaCategoryBatchTransferRequestDto request = new MetaCategoryBatchTransferRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setAtomic(true);
        request.setDryRun(false);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
            operation("OP-CONFLICT", fixture.sourceRootId, fixture.sourceChildId),
            operation("OP-ABORTED", fixture.otherRootId, fixture.targetParentId)
        ));

        MetaCategoryBatchTransferResponseDto response = crudService.batchTransfer(request);

        Assertions.assertEquals(2, response.getTotal());
        Assertions.assertEquals(0, response.getSuccessCount());
        Assertions.assertEquals(2, response.getFailureCount());
        Assertions.assertEquals("CATEGORY_TARGET_IN_DESCENDANT", response.getResults().get(0).getCode());
        Assertions.assertEquals("ATOMIC_ABORTED", response.getResults().get(1).getCode());

        MetaCategoryDef untouched = inNewTransaction(() -> defRepository.findById(fixture.otherRootId).orElseThrow());
        Assertions.assertNull(untouched.getParent());
    }

    private <T> T inNewTransaction(Supplier<T> supplier) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate template = new TransactionTemplate(transactionManager, definition);
        return template.execute(status -> supplier.get());
    }

    private MetaCategoryBatchTransferOperationDto operation(String clientOperationId, UUID sourceNodeId, UUID targetParentId) {
        MetaCategoryBatchTransferOperationDto dto = new MetaCategoryBatchTransferOperationDto();
        dto.setClientOperationId(clientOperationId);
        dto.setSourceNodeId(sourceNodeId);
        dto.setTargetParentId(targetParentId);
        return dto;
    }

    private MetaCategoryDef createNode(String businessDomain,
                                       String code,
                                       MetaCategoryDef parent,
                                       String status,
                                       int sort,
                                       short depth,
                                       boolean leaf) {
        MetaCategoryDef def = new MetaCategoryDef();
        def.setBusinessDomain(businessDomain);
        def.setCodeKey(code);
        def.setParent(parent);
        def.setStatus(status);
        def.setSortOrder(sort);
        def.setDepth(depth);
        def.setIsLeaf(leaf);
        def.setPath(buildPath(parent, code));
        def.setFullPathName(code);
        def.setCreatedBy("it-transfer");
        def = defRepository.save(def);

        MetaCategoryVersion version = new MetaCategoryVersion();
        version.setCategoryDef(def);
        version.setVersionNo(1);
        version.setDisplayName(code);
        version.setStructureJson("{}");
        version.setIsLatest(true);
        version.setCreatedBy("it-transfer");
        versionRepository.save(version);
        return def;
    }

    private void saveClosureRows(List<MetaCategoryDef> nodes) {
        Map<String, CategoryHierarchy> rows = new LinkedHashMap<>();
        for (MetaCategoryDef node : nodes) {
            MetaCategoryDef current = node;
            short distance = 0;
            while (current != null) {
                putHierarchy(rows, current, node, distance);
                current = current.getParent();
                distance++;
            }
        }
        hierarchyRepository.saveAll(new ArrayList<>(rows.values()));
    }

    private void putHierarchy(Map<String, CategoryHierarchy> rows,
                              MetaCategoryDef ancestor,
                              MetaCategoryDef descendant,
                              short distance) {
        String key = ancestor.getId() + "->" + descendant.getId();
        rows.putIfAbsent(key, createHierarchy(ancestor, descendant, distance));
    }

    private CategoryHierarchy createHierarchy(MetaCategoryDef ancestor, MetaCategoryDef descendant, short distance) {
        CategoryHierarchy hierarchy = new CategoryHierarchy();
        hierarchy.setAncestorDef(ancestor);
        hierarchy.setDescendantDef(descendant);
        hierarchy.setDistance(distance);
        return hierarchy;
    }

    private String buildPath(MetaCategoryDef parent, String code) {
        if (parent == null || parent.getPath() == null || parent.getPath().isBlank()) {
            return "/" + code;
        }
        return parent.getPath() + "/" + code;
    }

    private String uniqueCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private boolean isCommittedTestDef(MetaCategoryDef def) {
        return def != null
                && "it-transfer".equals(def.getCreatedBy())
                && def.getCodeKey() != null
                && def.getCodeKey().startsWith("IT-");
    }
}