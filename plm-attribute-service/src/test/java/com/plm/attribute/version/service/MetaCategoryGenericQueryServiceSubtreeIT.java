package com.plm.attribute.version.service;

import com.plm.common.api.dto.MetaCategorySubtreeFlatNodeDto;
import com.plm.common.api.dto.MetaCategorySubtreeRequestDto;
import com.plm.common.api.dto.MetaCategorySubtreeResponseDto;
import com.plm.common.api.dto.MetaCategorySubtreeTreeNodeDto;
import com.plm.common.version.domain.CategoryHierarchy;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
class MetaCategoryGenericQueryServiceSubtreeIT {

    private static final short TEST_ROOT_DEPTH = 1;

    @Autowired
    private MetaCategoryGenericQueryService queryService;

    @Autowired
    private MetaCategoryDefRepository defRepository;

    @Autowired
    private MetaCategoryVersionRepository versionRepository;

    @Autowired
    private CategoryHierarchyRepository hierarchyRepository;

    @Test
    void subtree_flat_shouldTruncateByNodeLimit() {
        MetaCategoryDef root = createNode("MATERIAL", "IT-SUBTREE-ROOT-1", null, "active", 0, TEST_ROOT_DEPTH, false);
        MetaCategoryDef childA = createNode("MATERIAL", "IT-SUBTREE-CHILD-1A", root, "active", 10, (short) (TEST_ROOT_DEPTH + 1), true);
        MetaCategoryDef childB = createNode("MATERIAL", "IT-SUBTREE-CHILD-1B", root, "active", 20, (short) (TEST_ROOT_DEPTH + 1), true);
        createClosure(root, List.of(root, childA, childB));

        MetaCategorySubtreeRequestDto request = new MetaCategorySubtreeRequestDto();
        request.setParentId(root.getId());
        request.setMode("FLAT");
        request.setNodeLimit(2);
        request.setStatus("ACTIVE");

        MetaCategorySubtreeResponseDto response = queryService.subtree(request);

        Assertions.assertEquals("FLAT", response.getMode());
        Assertions.assertTrue(Boolean.TRUE.equals(response.getTruncated()));
        Assertions.assertEquals(2, response.getTotalNodes());
        Assertions.assertNotNull(response.getMessage());

        @SuppressWarnings("unchecked")
        List<MetaCategorySubtreeFlatNodeDto> data = (List<MetaCategorySubtreeFlatNodeDto>) response.getData();
        Assertions.assertEquals(2, data.size());
    }

    @Test
    void subtree_flat_statusAll_shouldExcludeDeleted() {
        MetaCategoryDef root = createNode("MATERIAL", "IT-SUBTREE-ROOT-2", null, "active", 0, TEST_ROOT_DEPTH, false);
        MetaCategoryDef childDraft = createNode("MATERIAL", "IT-SUBTREE-CHILD-2A", root, "draft", 10, (short) (TEST_ROOT_DEPTH + 1), true);
        MetaCategoryDef childDeleted = createNode("MATERIAL", "IT-SUBTREE-CHILD-2B", root, "deleted", 20, (short) (TEST_ROOT_DEPTH + 1), true);
        createClosure(root, List.of(root, childDraft, childDeleted));

        MetaCategorySubtreeRequestDto request = new MetaCategorySubtreeRequestDto();
        request.setParentId(root.getId());
        request.setMode("FLAT");
        request.setNodeLimit(20);
        request.setStatus("ALL");

        MetaCategorySubtreeResponseDto response = queryService.subtree(request);

        @SuppressWarnings("unchecked")
        List<MetaCategorySubtreeFlatNodeDto> data = (List<MetaCategorySubtreeFlatNodeDto>) response.getData();
        List<String> codes = data.stream().map(MetaCategorySubtreeFlatNodeDto::getCode).toList();

        Assertions.assertTrue(codes.contains("IT-SUBTREE-ROOT-2"));
        Assertions.assertTrue(codes.contains("IT-SUBTREE-CHILD-2A"));
        Assertions.assertFalse(codes.contains("IT-SUBTREE-CHILD-2B"));
    }

    @Test
    void subtree_tree_shouldBuildNestedChildren() {
        MetaCategoryDef root = createNode("MATERIAL", "IT-SUBTREE-ROOT-3", null, "active", 0, TEST_ROOT_DEPTH, false);
        MetaCategoryDef child = createNode("MATERIAL", "IT-SUBTREE-CHILD-3A", root, "active", 10, (short) (TEST_ROOT_DEPTH + 1), false);
        MetaCategoryDef grandchild = createNode("MATERIAL", "IT-SUBTREE-GRAND-3A", child, "active", 10, (short) (TEST_ROOT_DEPTH + 2), true);
        createClosure(root, List.of(root, child, grandchild));

        MetaCategorySubtreeRequestDto request = new MetaCategorySubtreeRequestDto();
        request.setParentId(root.getId());
        request.setMode("TREE");
        request.setStatus("ACTIVE");
        request.setNodeLimit(20);

        MetaCategorySubtreeResponseDto response = queryService.subtree(request);

        Assertions.assertEquals("TREE", response.getMode());
        Assertions.assertEquals(2, response.getDepthReached());
        Assertions.assertTrue(response.getData() instanceof MetaCategorySubtreeTreeNodeDto);

        MetaCategorySubtreeTreeNodeDto rootNode = (MetaCategorySubtreeTreeNodeDto) response.getData();
        Assertions.assertEquals("IT-SUBTREE-ROOT-3", rootNode.getCode());
        Assertions.assertEquals(1, rootNode.getChildren().size());
        Assertions.assertEquals("IT-SUBTREE-CHILD-3A", rootNode.getChildren().get(0).getCode());
        Assertions.assertEquals(1, rootNode.getChildren().get(0).getChildren().size());
        Assertions.assertEquals("IT-SUBTREE-GRAND-3A", rootNode.getChildren().get(0).getChildren().get(0).getCode());
    }

    @Test
    void subtree_flat_shouldRespectMaxDepth() {
        MetaCategoryDef root = createNode("MATERIAL", "IT-SUBTREE-ROOT-4", null, "active", 0, TEST_ROOT_DEPTH, false);
        MetaCategoryDef child = createNode("MATERIAL", "IT-SUBTREE-CHILD-4A", root, "active", 10, (short) (TEST_ROOT_DEPTH + 1), false);
        MetaCategoryDef grandchild = createNode("MATERIAL", "IT-SUBTREE-GRAND-4A", child, "active", 10, (short) (TEST_ROOT_DEPTH + 2), true);
        createClosure(root, List.of(root, child, grandchild));

        MetaCategorySubtreeRequestDto request = new MetaCategorySubtreeRequestDto();
        request.setParentId(root.getId());
        request.setMode("FLAT");
        request.setStatus("ACTIVE");
        request.setNodeLimit(20);
        request.setMaxDepth(1);

        MetaCategorySubtreeResponseDto response = queryService.subtree(request);

        @SuppressWarnings("unchecked")
        List<MetaCategorySubtreeFlatNodeDto> data = (List<MetaCategorySubtreeFlatNodeDto>) response.getData();
        List<String> codes = data.stream().map(MetaCategorySubtreeFlatNodeDto::getCode).toList();

        Assertions.assertTrue(codes.contains("IT-SUBTREE-ROOT-4"));
        Assertions.assertTrue(codes.contains("IT-SUBTREE-CHILD-4A"));
        Assertions.assertFalse(codes.contains("IT-SUBTREE-GRAND-4A"));
        Assertions.assertEquals(1, response.getDepthReached());
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
        def.setCreatedBy("it-subtree");
        def = defRepository.save(def);

        MetaCategoryVersion version = new MetaCategoryVersion();
        version.setCategoryDef(def);
        version.setVersionNo(1);
        version.setDisplayName(code);
        version.setStructureJson("{}");
        version.setIsLatest(true);
        version.setCreatedBy("it-subtree");
        versionRepository.save(version);
        return def;
    }

    private void createClosure(MetaCategoryDef root, List<MetaCategoryDef> nodes) {
        java.util.LinkedHashMap<String, CategoryHierarchy> rows = new java.util.LinkedHashMap<>();
        for (MetaCategoryDef node : nodes) {
            int distance = calcDistance(root, node);
            if (distance >= 0) {
                putHierarchy(rows, root, node, (short) distance);
            }
        }

        for (MetaCategoryDef node : nodes) {
            putHierarchy(rows, node, node, (short) 0);
            if (node.getParent() != null) {
                putHierarchy(rows, node.getParent(), node, (short) 1);
            }
        }
        hierarchyRepository.saveAll(rows.values());
    }

    private void putHierarchy(java.util.Map<String, CategoryHierarchy> rows,
                              MetaCategoryDef ancestor,
                              MetaCategoryDef descendant,
                              short distance) {
        String key = ancestor.getId() + "->" + descendant.getId();
        rows.putIfAbsent(key, createHierarchy(ancestor, descendant, distance));
    }

    private CategoryHierarchy createHierarchy(MetaCategoryDef ancestor, MetaCategoryDef descendant, short distance) {
        CategoryHierarchy h = new CategoryHierarchy();
        h.setAncestorDef(ancestor);
        h.setDescendantDef(descendant);
        h.setDistance(distance);
        return h;
    }

    private int calcDistance(MetaCategoryDef root, MetaCategoryDef node) {
        int distance = 0;
        MetaCategoryDef current = node;
        while (current != null) {
            if (current.getId().equals(root.getId())) {
                return distance;
            }
            current = current.getParent();
            distance++;
        }
        return -1;
    }

    private String buildPath(MetaCategoryDef parent, String code) {
        if (parent == null || parent.getPath() == null || parent.getPath().isBlank()) {
            return "/" + code;
        }
        return parent.getPath() + "/" + code;
    }
}
