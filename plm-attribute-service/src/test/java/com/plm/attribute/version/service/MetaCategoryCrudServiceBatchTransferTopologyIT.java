package com.plm.attribute.version.service;

import com.plm.common.api.dto.MetaCategoryBatchTransferTopologyOperationDto;
import com.plm.common.api.dto.MetaCategoryBatchTransferTopologyRequestDto;
import com.plm.common.api.dto.MetaCategoryBatchTransferTopologyResponseDto;
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
class MetaCategoryCrudServiceBatchTransferTopologyIT {

    private static final short TEST_ROOT_DEPTH = 1;

    private static final class TopologyFixture {
        private UUID workspaceRootId;
        private UUID rootAId;
        private UUID childBId;
        private UUID targetXId;
        private UUID targetYId;
        private UUID targetXChildId;
        private UUID targetYChildId;
        private UUID sourceSiblingCId;
        private UUID sourceSiblingCLeafId;
        private UUID childBLeafId;
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
    void batchTransferTopology_dryRun_shouldReturnResolvedPlanForDescendantFirstMove() {
        TopologyFixture fixture = createTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(true);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-b-to-y", fixture.childBId, fixture.targetYId, List.of(), fixture.rootAId),
                topologyOperation("op-a-to-x", fixture.rootAId, fixture.targetXId, List.of("op-b-to-y"), null)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(2, response.getTotal());
        Assertions.assertEquals(2, response.getSuccessCount(), String.valueOf(response));
        Assertions.assertEquals(0, response.getFailureCount());
        Assertions.assertEquals(List.of("op-b-to-y", "op-a-to-x"), response.getResolvedOrder());
        Assertions.assertEquals(2, response.getResults().size());
        Assertions.assertTrue(response.getResults().stream().allMatch(result -> Boolean.TRUE.equals(result.getSuccess())));
        Assertions.assertEquals(2, response.getFinalParentMappings().size());
    }

    @Test
    void batchTransferTopology_dryRun_shouldRejectAncestorBeforeDescendantWhenDependsOnOrderInvalid() {
        TopologyFixture fixture = createTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(true);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-a-to-x", fixture.rootAId, fixture.targetXId, List.of("op-b-to-y"), null),
                topologyOperation("op-b-to-y", fixture.childBId, fixture.targetYId, List.of(), fixture.rootAId)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(2, response.getTotal());
        Assertions.assertTrue(response.getFailureCount() >= 1);
        Assertions.assertEquals("CATEGORY_OPERATION_ORDER_INVALID", response.getResults().get(0).getCode());
    }

    @Test
    void batchTransferTopology_dryRun_shouldRejectBatchTreeCycle() {
        TopologyFixture fixture = inNewTransaction(() -> {
            TopologyFixture created = new TopologyFixture();
            MetaCategoryDef rootA = createNode("MATERIAL", uniqueCode("IT-TOPO-CYCLE-A"), null, "active", 1, TEST_ROOT_DEPTH, true);
            MetaCategoryDef rootB = createNode("MATERIAL", uniqueCode("IT-TOPO-CYCLE-B"), null, "active", 2, TEST_ROOT_DEPTH, true);
            saveClosureRows(List.of(rootA, rootB));
            created.rootAId = rootA.getId();
            created.childBId = rootB.getId();
            return created;
        });

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(true);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-a-to-b", fixture.rootAId, fixture.childBId, List.of(), null),
                topologyOperation("op-b-to-a", fixture.childBId, fixture.rootAId, List.of("op-a-to-b"), null)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(2, response.getTotal());
        Assertions.assertEquals(1, response.getFailureCount());
        Assertions.assertEquals("CATEGORY_EFFECTIVE_TARGET_IN_DESCENDANT", response.getResults().get(1).getCode());
    }

    @Test
    void batchTransferTopology_dryRun_shouldAllowMissingExpectedSourceParentId() {
        TopologyFixture fixture = createTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(true);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-b-to-y", fixture.childBId, fixture.targetYId, List.of(), null)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(1, response.getSuccessCount());
        Assertions.assertEquals(0, response.getFailureCount());
        Assertions.assertTrue(Boolean.TRUE.equals(response.getResults().get(0).getSuccess()));
    }

    @Test
    void batchTransferTopology_dryRun_shouldRejectExpectedParentMismatchWhenProvided() {
        TopologyFixture fixture = createTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(true);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-b-to-y", fixture.childBId, fixture.targetYId, List.of(), fixture.targetXId)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(0, response.getSuccessCount());
        Assertions.assertEquals(1, response.getFailureCount());
        Assertions.assertEquals("CATEGORY_EXPECTED_PARENT_MISMATCH", response.getResults().get(0).getCode());
    }

    @Test
    void batchTransferTopology_execute_shouldMoveNodesInResolvedOrder() {
        TopologyFixture fixture = createTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(false);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-b-to-y", fixture.childBId, fixture.targetYId, List.of(), fixture.rootAId),
                topologyOperation("op-a-to-x", fixture.rootAId, fixture.targetXId, List.of("op-b-to-y"), null)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(2, response.getSuccessCount());
        MetaCategoryDef movedA = inNewTransaction(() -> defRepository.findById(fixture.rootAId).orElseThrow());
        MetaCategoryDef movedB = inNewTransaction(() -> defRepository.findById(fixture.childBId).orElseThrow());
        Assertions.assertEquals(fixture.targetXId, movedA.getParent().getId());
        Assertions.assertEquals(fixture.targetYId, movedB.getParent().getId());
    }

    @Test
    void batchTransferTopology_execute_shouldMoveTreeToParentThenBackToRoot() {
        TopologyFixture fixture = createTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto moveToParentRequest = new MetaCategoryBatchTransferTopologyRequestDto();
        moveToParentRequest.setBusinessDomain("MATERIAL");
        moveToParentRequest.setAction("MOVE");
        moveToParentRequest.setDryRun(false);
        moveToParentRequest.setAtomic(true);
        moveToParentRequest.setOperator("it-transfer");
        moveToParentRequest.setOperations(List.of(
                topologyOperation("op-a-to-x", fixture.rootAId, fixture.targetXId, List.of(), null)
        ));

        MetaCategoryBatchTransferTopologyResponseDto moveToParentResponse = inNewTransaction(
            () -> crudService.batchTransferTopology(moveToParentRequest)
        );
        Assertions.assertEquals(1, moveToParentResponse.getSuccessCount(), String.valueOf(moveToParentResponse));
        Assertions.assertEquals(0, moveToParentResponse.getFailureCount(), String.valueOf(moveToParentResponse));

        MetaCategoryDef movedUnderTarget = inNewTransaction(() -> defRepository.findById(fixture.rootAId).orElseThrow());
        Assertions.assertEquals(fixture.targetXId, movedUnderTarget.getParent().getId());

        MetaCategoryBatchTransferTopologyRequestDto moveBackToRootRequest = new MetaCategoryBatchTransferTopologyRequestDto();
        moveBackToRootRequest.setBusinessDomain("MATERIAL");
        moveBackToRootRequest.setAction("MOVE");
        moveBackToRootRequest.setDryRun(false);
        moveBackToRootRequest.setAtomic(true);
        moveBackToRootRequest.setOperator("it-transfer");
        moveBackToRootRequest.setOperations(List.of(
                topologyOperation("op-a-back-to-root", fixture.rootAId, null, List.of(), fixture.targetXId)
        ));

        MetaCategoryBatchTransferTopologyResponseDto moveBackToRootResponse = inNewTransaction(
            () -> crudService.batchTransferTopology(moveBackToRootRequest)
        );
        Assertions.assertEquals(1, moveBackToRootResponse.getSuccessCount(), String.valueOf(moveBackToRootResponse));
        Assertions.assertEquals(0, moveBackToRootResponse.getFailureCount(), String.valueOf(moveBackToRootResponse));

        MetaCategoryDef movedBackToRoot = inNewTransaction(() -> defRepository.findById(fixture.rootAId).orElseThrow());
        MetaCategoryDef childAfterMoveBack = inNewTransaction(() -> defRepository.findById(fixture.childBId).orElseThrow());
        Assertions.assertNull(movedBackToRoot.getParent());
        Assertions.assertEquals(TEST_ROOT_DEPTH, movedBackToRoot.getDepth());
        Assertions.assertEquals("/" + movedBackToRoot.getCodeKey(), movedBackToRoot.getPath());
        Assertions.assertEquals(fixture.rootAId, childAfterMoveBack.getParent().getId());
    }

    @Test
    void batchTransferTopology_execute_shouldSupportImportedChildrenThenMoveAncestorTree() {
        TopologyFixture fixture = createComplexTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(false);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-c-to-x-child", fixture.sourceSiblingCId, fixture.targetXChildId, List.of(), fixture.rootAId),
                topologyOperation("op-b-to-y-child", fixture.childBId, fixture.targetYChildId, List.of(), fixture.rootAId),
                topologyOperation("op-root-a-to-target-x", fixture.rootAId, fixture.targetXId, List.of(
                        "op-c-to-x-child",
                        "op-b-to-y-child"
                ), fixture.workspaceRootId)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(3, response.getSuccessCount(), String.valueOf(response));
        Assertions.assertEquals(0, response.getFailureCount(), String.valueOf(response));

        MetaCategoryDef movedRoot = inNewTransaction(() -> defRepository.findById(fixture.rootAId).orElseThrow());
        MetaCategoryDef movedB = inNewTransaction(() -> defRepository.findById(fixture.childBId).orElseThrow());
        MetaCategoryDef movedC = inNewTransaction(() -> defRepository.findById(fixture.sourceSiblingCId).orElseThrow());
        MetaCategoryDef movedBLeaf = inNewTransaction(() -> defRepository.findById(fixture.childBLeafId).orElseThrow());
        MetaCategoryDef movedCLeaf = inNewTransaction(() -> defRepository.findById(fixture.sourceSiblingCLeafId).orElseThrow());

        Assertions.assertEquals(fixture.targetXId, movedRoot.getParent().getId());
        Assertions.assertEquals(fixture.targetYChildId, movedB.getParent().getId());
        Assertions.assertEquals(fixture.targetXChildId, movedC.getParent().getId());
        Assertions.assertEquals(fixture.childBId, movedBLeaf.getParent().getId());
        Assertions.assertEquals(fixture.sourceSiblingCId, movedCLeaf.getParent().getId());
    }

    @Test
    void batchTransferTopology_dryRun_shouldResolveImportedChildrenThenMoveAncestorTree() {
        TopologyFixture fixture = createComplexTopologyFixture();

        MetaCategoryBatchTransferTopologyRequestDto request = new MetaCategoryBatchTransferTopologyRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setAction("MOVE");
        request.setDryRun(true);
        request.setAtomic(true);
        request.setOperator("it-transfer");
        request.setOperations(List.of(
                topologyOperation("op-c-to-x-child", fixture.sourceSiblingCId, fixture.targetXChildId, List.of(), fixture.rootAId),
                topologyOperation("op-b-to-y-child", fixture.childBId, fixture.targetYChildId, List.of(), fixture.rootAId),
                topologyOperation("op-root-a-to-target-x", fixture.rootAId, fixture.targetXId, List.of(
                        "op-c-to-x-child",
                        "op-b-to-y-child"
                ), fixture.workspaceRootId)
        ));

        MetaCategoryBatchTransferTopologyResponseDto response = crudService.batchTransferTopology(request);

        Assertions.assertEquals(3, response.getSuccessCount());
        Assertions.assertEquals(0, response.getFailureCount());
        Assertions.assertEquals(List.of(
                "op-c-to-x-child",
                "op-b-to-y-child",
                "op-root-a-to-target-x"
        ), response.getResolvedOrder());
        Assertions.assertEquals(3, response.getFinalParentMappings().size());
    }

    private TopologyFixture createTopologyFixture() {
        return inNewTransaction(() -> {
            TopologyFixture created = new TopologyFixture();
            MetaCategoryDef rootA = createNode("MATERIAL", uniqueCode("IT-TOPO-A"), null, "active", 1, TEST_ROOT_DEPTH, false);
            MetaCategoryDef childB = createNode("MATERIAL", uniqueCode("IT-TOPO-B"), rootA, "active", 1, (short) (TEST_ROOT_DEPTH + 1), true);
            MetaCategoryDef targetX = createNode("MATERIAL", uniqueCode("IT-TOPO-X"), null, "active", 2, TEST_ROOT_DEPTH, true);
            MetaCategoryDef targetY = createNode("MATERIAL", uniqueCode("IT-TOPO-Y"), null, "active", 3, TEST_ROOT_DEPTH, true);
            saveClosureRows(List.of(rootA, childB, targetX, targetY));
            created.rootAId = rootA.getId();
            created.childBId = childB.getId();
            created.targetXId = targetX.getId();
            created.targetYId = targetY.getId();
            return created;
        });
    }

    private TopologyFixture createComplexTopologyFixture() {
        return inNewTransaction(() -> {
            TopologyFixture created = new TopologyFixture();
            MetaCategoryDef workspaceRoot = createNode("MATERIAL", uniqueCode("IT-TOPO-WORKSPACE"), null, "active", 1, TEST_ROOT_DEPTH, false);
            MetaCategoryDef rootA = createNode("MATERIAL", uniqueCode("IT-TOPO-A-CPLX"), workspaceRoot, "active", 1, (short) (TEST_ROOT_DEPTH + 1), false);
            MetaCategoryDef childB = createNode("MATERIAL", uniqueCode("IT-TOPO-B-CPLX"), rootA, "active", 1, (short) (TEST_ROOT_DEPTH + 2), false);
            MetaCategoryDef childBLeaf = createNode("MATERIAL", uniqueCode("IT-TOPO-B-LEAF"), childB, "active", 1, (short) (TEST_ROOT_DEPTH + 3), true);
            MetaCategoryDef childC = createNode("MATERIAL", uniqueCode("IT-TOPO-C-CPLX"), rootA, "active", 2, (short) (TEST_ROOT_DEPTH + 2), false);
            MetaCategoryDef childCLeaf = createNode("MATERIAL", uniqueCode("IT-TOPO-C-LEAF"), childC, "active", 1, (short) (TEST_ROOT_DEPTH + 3), true);
            MetaCategoryDef targetX = createNode("MATERIAL", uniqueCode("IT-TOPO-X-CPLX"), workspaceRoot, "active", 2, (short) (TEST_ROOT_DEPTH + 1), false);
            MetaCategoryDef targetXChild = createNode("MATERIAL", uniqueCode("IT-TOPO-X-CHILD"), targetX, "active", 1, (short) (TEST_ROOT_DEPTH + 2), true);
            MetaCategoryDef targetY = createNode("MATERIAL", uniqueCode("IT-TOPO-Y-CPLX"), rootA, "active", 3, (short) (TEST_ROOT_DEPTH + 2), false);
            MetaCategoryDef targetYChild = createNode("MATERIAL", uniqueCode("IT-TOPO-Y-CHILD"), targetY, "active", 1, (short) (TEST_ROOT_DEPTH + 3), true);

            saveClosureRows(List.of(
                    workspaceRoot,
                    rootA,
                    childB,
                    childBLeaf,
                    childC,
                    childCLeaf,
                    targetX,
                    targetXChild,
                    targetY,
                    targetYChild
            ));

            created.workspaceRootId = workspaceRoot.getId();
            created.rootAId = rootA.getId();
            created.childBId = childB.getId();
            created.childBLeafId = childBLeaf.getId();
            created.sourceSiblingCId = childC.getId();
            created.sourceSiblingCLeafId = childCLeaf.getId();
            created.targetXId = targetX.getId();
            created.targetXChildId = targetXChild.getId();
            created.targetYId = targetY.getId();
            created.targetYChildId = targetYChild.getId();
            return created;
        });
    }

    private <T> T inNewTransaction(Supplier<T> supplier) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate template = new TransactionTemplate(transactionManager, definition);
        return template.execute(status -> supplier.get());
    }

    private MetaCategoryBatchTransferTopologyOperationDto topologyOperation(String operationId,
                                                                           UUID sourceNodeId,
                                                                           UUID targetParentId,
                                                                           List<String> dependsOnOperationIds,
                                                                           UUID expectedSourceParentId) {
        MetaCategoryBatchTransferTopologyOperationDto dto = new MetaCategoryBatchTransferTopologyOperationDto();
        dto.setOperationId(operationId);
        dto.setSourceNodeId(sourceNodeId);
        dto.setTargetParentId(targetParentId);
        dto.setDependsOnOperationIds(dependsOnOperationIds);
        dto.setAllowDescendantFirstSplit(Boolean.TRUE);
        dto.setExpectedSourceParentId(expectedSourceParentId);
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
        def.setFullPathName(parent == null ? code : parent.getFullPathName() + "/" + code);
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