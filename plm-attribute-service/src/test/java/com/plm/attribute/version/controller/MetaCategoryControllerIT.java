package com.plm.attribute.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.attribute.version.service.MetaCategoryCrudService;
import com.plm.common.api.dto.category.CreateCategoryCodePreviewRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.MetaCategoryChildrenBatchRequestDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import com.plm.common.api.dto.category.UpdateCategoryRequestDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchDeleteRequestDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferOperationDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferRequestDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferTopologyOperationDto;
import com.plm.common.api.dto.category.batch.MetaCategoryBatchTransferTopologyRequestDto;
import com.plm.common.api.dto.category.subtree.MetaCategorySubtreeRequestDto;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
public class MetaCategoryControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaCategoryDefRepository categoryDefRepository;

    @Test
    void crudEndpoints_shouldSupportLifecycleAndCompareVersions() throws Exception {
        String suffix = uniqueSuffix();

        CreateCategoryCodePreviewRequestDto previewRequest = new CreateCategoryCodePreviewRequestDto();
        previewRequest.setBusinessDomain("MATERIAL");
        previewRequest.setCount(1);

        mockMvc.perform(post("/api/meta/categories/code-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(previewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDomain").value("MATERIAL"))
                .andExpect(jsonPath("$.ruleCode").value("CATEGORY"))
                .andExpect(jsonPath("$.suggestedCode").isNotEmpty());

        CreateCategoryRequestDto createRequest = new CreateCategoryRequestDto();
        createRequest.setBusinessDomain("MATERIAL");
        createRequest.setName("Category Http " + suffix);
        createRequest.setDescription("created-" + suffix);
        createRequest.setStatus("ACTIVE");
        createRequest.setSort(10);

        MvcResult createResult = mockMvc.perform(post("/api/meta/categories")
                        .param("operator", "creator-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.businessDomain").value("MATERIAL"))
                .andExpect(jsonPath("$.latestVersion.name").value("Category Http " + suffix))
                .andReturn();

        MetaCategoryDetailDto created = objectMapper.readValue(
                createResult.getResponse().getContentAsByteArray(),
                MetaCategoryDetailDto.class);

        mockMvc.perform(get("/api/meta/categories/{id}", created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.code").value(created.getCode()))
                .andExpect(jsonPath("$.createdBy").value("creator-user"));

        UpdateCategoryRequestDto updateRequest = new UpdateCategoryRequestDto();
        updateRequest.setBusinessDomain("MATERIAL");
        updateRequest.setCode(created.getCode());
        updateRequest.setName("Category Updated " + suffix);
        updateRequest.setDescription("updated-" + suffix);
        updateRequest.setStatus("ACTIVE");
        updateRequest.setSort(20);

        mockMvc.perform(put("/api/meta/categories/{id}", created.getId())
                        .param("operator", "update-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.versionNo").value(2))
                .andExpect(jsonPath("$.latestVersion.name").value("Category Updated " + suffix));

        UpdateCategoryRequestDto patchRequest = new UpdateCategoryRequestDto();
        patchRequest.setBusinessDomain("MATERIAL");
        patchRequest.setName("Category Patched " + suffix);
        patchRequest.setDescription("patched-" + suffix);

        mockMvc.perform(patch("/api/meta/categories/{id}", created.getId())
                        .param("operator", "patch-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(patchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.versionNo").value(3))
                .andExpect(jsonPath("$.latestVersion.name").value("Category Patched " + suffix));

        MetaCategoryDetailDto latest = categoryCrudService.detail(created.getId());
        UUID baseVersionId = latest.getHistoryVersions().stream()
                .filter(item -> Integer.valueOf(1).equals(item.getVersionNo()))
                .findFirst()
                .orElseThrow()
                .getVersionId();
        UUID targetVersionId = latest.getHistoryVersions().stream()
                .filter(item -> Integer.valueOf(3).equals(item.getVersionNo()))
                .findFirst()
                .orElseThrow()
                .getVersionId();

        mockMvc.perform(get("/api/meta/categories/{id}/versions/compare", created.getId())
                        .param("baseVersionId", baseVersionId.toString())
                        .param("targetVersionId", targetVersionId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(created.getId().toString()))
                .andExpect(jsonPath("$.categoryCode").value(created.getCode()))
                .andExpect(jsonPath("$.baseVersion.versionNo").value(1))
                .andExpect(jsonPath("$.targetVersion.versionNo").value(3));

        mockMvc.perform(delete("/api/meta/categories/{id}", created.getId())
                        .param("cascade", "false")
                        .param("confirm", "false")
                        .param("operator", "delete-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.deletedCount").value(1));

        org.junit.jupiter.api.Assertions.assertEquals(
                "deleted",
                categoryDefRepository.findById(created.getId()).orElseThrow().getStatus());
    }

    @Test
    void genericQueryEndpoints_shouldReturnNodesPathSearchChildrenBatchAndSubtree() throws Exception {
        String suffix = uniqueSuffix();
        MetaCategoryDetailDto root = createCategory("MATERIAL", "CAT-NODE-ROOT-" + suffix, "Node Root " + suffix, null);
        MetaCategoryDetailDto child = createCategory("MATERIAL", "CAT-NODE-CHILD-" + suffix, "Node Child " + suffix, root.getId());
        MetaCategoryDetailDto grandchild = createCategory("MATERIAL", "CAT-NODE-GRAND-" + suffix, "Node Grand " + suffix, child.getId());

        mockMvc.perform(get("/api/meta/categories/nodes")
                        .param("businessDomain", "MATERIAL")
                        .param("parentId", root.getId().toString())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(child.getId().toString()))
                .andExpect(jsonPath("$.content[0].code").value(child.getCode()));

        mockMvc.perform(get("/api/meta/categories/nodes/{id}/path", grandchild.getId())
                        .param("businessDomain", "MATERIAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value(root.getCode()))
                .andExpect(jsonPath("$[1].code").value(child.getCode()))
                .andExpect(jsonPath("$[2].code").value(grandchild.getCode()));

        mockMvc.perform(get("/api/meta/categories/search")
                        .param("businessDomain", "MATERIAL")
                        .param("keyword", "Grand " + suffix)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].node.code").value(grandchild.getCode()))
                .andExpect(jsonPath("$.content[0].path").value(containsString(root.getCode())));

        MetaCategoryChildrenBatchRequestDto childrenBatchRequest = new MetaCategoryChildrenBatchRequestDto();
        childrenBatchRequest.setBusinessDomain("MATERIAL");
        childrenBatchRequest.setStatus("ACTIVE");
        childrenBatchRequest.setParentIds(List.of(root.getId(), child.getId()));

        mockMvc.perform(post("/api/meta/categories/nodes:children-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(childrenBatchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + root.getId() + "'][0].code").value(child.getCode()))
                .andExpect(jsonPath("$['" + child.getId() + "'][0].code").value(grandchild.getCode()));

        MetaCategorySubtreeRequestDto subtreeRequest = new MetaCategorySubtreeRequestDto();
        subtreeRequest.setParentId(root.getId());
        subtreeRequest.setIncludeRoot(Boolean.TRUE);
        subtreeRequest.setMode("TREE");
        subtreeRequest.setStatus("ACTIVE");
        subtreeRequest.setNodeLimit(20);

        mockMvc.perform(post("/api/meta/categories/nodes/subtree")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(subtreeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TREE"))
                .andExpect(jsonPath("$.data.code").value(root.getCode()))
                .andExpect(jsonPath("$.data.children[0].code").value(child.getCode()))
                .andExpect(jsonPath("$.data.children[0].children[0].code").value(grandchild.getCode()));
    }

    @Test
    void batchEndpoints_shouldSupportDeleteTransferAndTopology() throws Exception {
        String suffix = uniqueSuffix();

        MetaCategoryDetailDto deleteLeaf = createCategory("MATERIAL", "CAT-BATCH-DEL-" + suffix, "Batch Delete " + suffix, null);

        MetaCategoryBatchDeleteRequestDto deleteRequest = new MetaCategoryBatchDeleteRequestDto();
        deleteRequest.setIds(List.of(deleteLeaf.getId()));
        deleteRequest.setCascade(Boolean.FALSE);
        deleteRequest.setConfirm(Boolean.FALSE);
        deleteRequest.setAtomic(Boolean.FALSE);
        deleteRequest.setDryRun(Boolean.TRUE);
        deleteRequest.setOperator("batch-user");

        mockMvc.perform(post("/api/meta/categories/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(deleteRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.totalWouldDeleteCount").value(1));

        MetaCategoryDetailDto copySourceRoot = createCategory("MATERIAL", "CAT-COPY-SRC-" + suffix, "Copy Source Root " + suffix, null);
        createCategory("MATERIAL", "CAT-COPY-CHILD-" + suffix, "Copy Source Child " + suffix, copySourceRoot.getId());
        MetaCategoryDetailDto targetParent = createCategory("MATERIAL", "CAT-COPY-TARGET-" + suffix, "Copy Target " + suffix, null);

        MetaCategoryBatchTransferRequestDto transferRequest = new MetaCategoryBatchTransferRequestDto();
        transferRequest.setBusinessDomain("MATERIAL");
        transferRequest.setAction("COPY");
        transferRequest.setAtomic(Boolean.FALSE);
        transferRequest.setDryRun(Boolean.TRUE);
        transferRequest.setOperator("transfer-user");
        transferRequest.setTargetParentId(targetParent.getId());
        transferRequest.setOperations(List.of(transferOperation("copy-op", copySourceRoot.getId(), null)));

        mockMvc.perform(post("/api/meta/categories/batch-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(transferRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.copiedCount").value(2))
                .andExpect(jsonPath("$.results[0].affectedNodeCount").value(2));

        MetaCategoryDetailDto moveRoot = createCategory("MATERIAL", "CAT-TOPO-ROOT-" + suffix, "Topo Root " + suffix, null);
        MetaCategoryDetailDto moveChild = createCategory("MATERIAL", "CAT-TOPO-CHILD-" + suffix, "Topo Child " + suffix, moveRoot.getId());
        MetaCategoryDetailDto topoTarget = createCategory("MATERIAL", "CAT-TOPO-TARGET-" + suffix, "Topo Target " + suffix, null);

        MetaCategoryBatchTransferTopologyRequestDto topologyRequest = new MetaCategoryBatchTransferTopologyRequestDto();
        topologyRequest.setBusinessDomain("MATERIAL");
        topologyRequest.setAction("MOVE");
        topologyRequest.setAtomic(Boolean.TRUE);
        topologyRequest.setDryRun(Boolean.TRUE);
        topologyRequest.setOperator("topology-user");
        topologyRequest.setOperations(List.of(topologyOperation("topology-op", moveChild.getId(), topoTarget.getId(), moveRoot.getId())));

        mockMvc.perform(post("/api/meta/categories/batch-transfer/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(topologyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.resolvedOrder[0]").value("topology-op"))
                .andExpect(jsonPath("$.results[0].sourceNodeId").value(moveChild.getId().toString()));
    }

    private MetaCategoryDetailDto createCategory(String businessDomain, String code, String name, UUID parentId) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain(businessDomain);
        request.setCode(code);
        request.setName(name);
        request.setParentId(parentId);
        request.setStatus("ACTIVE");
        request.setDescription(name + " description");
        request.setSort(1);
        return categoryCrudService.create(request, "it-user");
    }

    private MetaCategoryBatchTransferOperationDto transferOperation(String clientOperationId, UUID sourceNodeId, UUID targetParentId) {
        MetaCategoryBatchTransferOperationDto dto = new MetaCategoryBatchTransferOperationDto();
        dto.setClientOperationId(clientOperationId);
        dto.setSourceNodeId(sourceNodeId);
        dto.setTargetParentId(targetParentId);
        return dto;
    }

    private MetaCategoryBatchTransferTopologyOperationDto topologyOperation(String operationId,
                                                                            UUID sourceNodeId,
                                                                            UUID targetParentId,
                                                                            UUID expectedSourceParentId) {
        MetaCategoryBatchTransferTopologyOperationDto dto = new MetaCategoryBatchTransferTopologyOperationDto();
        dto.setOperationId(operationId);
        dto.setSourceNodeId(sourceNodeId);
        dto.setTargetParentId(targetParentId);
        dto.setExpectedSourceParentId(expectedSourceParentId);
        dto.setDependsOnOperationIds(List.of());
        return dto;
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}