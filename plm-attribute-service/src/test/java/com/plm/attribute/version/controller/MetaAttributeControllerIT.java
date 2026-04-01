package com.plm.attribute.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.attribute.version.service.MetaAttributeManageService;
import com.plm.attribute.version.service.MetaCodeRuleService;
import com.plm.attribute.version.service.MetaCodeRuleSetService;
import com.plm.attribute.version.service.MetaCategoryCrudService;
import com.plm.common.api.dto.attribute.CreateAttributeCodePreviewRequestDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
class MetaAttributeControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeManageService attributeManageService;

        @Autowired
        private MetaCodeRuleService codeRuleService;

        @Autowired
        private MetaCodeRuleSetService codeRuleSetService;

    @Test
    void manageAndQueryEndpoints_shouldSupportHttpRoundTrip() throws Exception {
        String suffix = uniqueSuffix();
        String categoryCode = "MAT-HTTP-" + suffix;
        String attributeKey = "ATTR_HTTP_" + suffix;
        createCategory("MATERIAL", categoryCode, "Material Http " + suffix);

        CreateAttributeCodePreviewRequestDto previewRequest = new CreateAttributeCodePreviewRequestDto();
        previewRequest.setDataType("enum");
        previewRequest.setCount(2);

        mockMvc.perform(post("/api/meta/attribute-defs/code-preview")
                        .param("categoryCode", categoryCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(previewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDomain").value("MATERIAL"))
                .andExpect(jsonPath("$.categoryCode").value(categoryCode))
                .andExpect(jsonPath("$.suggestedCode").isNotEmpty());

        MetaAttributeUpsertRequestDto createRequest = textAttribute(attributeKey, "HTTP Attribute " + suffix, "httpField" + suffix);
        mockMvc.perform(post("/api/meta/attribute-defs")
                        .param("businessDomain", "MATERIAL")
                        .param("categoryCode", categoryCode)
                        .header("X-User", "http-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(attributeKey))
                .andExpect(jsonPath("$.businessDomain").value("MATERIAL"))
                .andExpect(jsonPath("$.categoryCode").value(categoryCode))
                .andExpect(jsonPath("$.createdBy").value("http-user"))
                .andExpect(jsonPath("$.latestVersion.versionNo").value(1))
                .andExpect(jsonPath("$.latestVersion.displayName").value("HTTP Attribute " + suffix));

        MetaAttributeUpsertRequestDto updateRequest = textAttribute(attributeKey, "HTTP Attribute Updated " + suffix, "httpField" + suffix);
        mockMvc.perform(put("/api/meta/attribute-defs/{attrKey}", attributeKey)
                        .param("businessDomain", "MATERIAL")
                        .param("categoryCode", categoryCode)
                        .param("includeValues", "false")
                        .param("createdBy", "query-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.versionNo").value(2))
                .andExpect(jsonPath("$.latestVersion.displayName").value("HTTP Attribute Updated " + suffix));

        MetaAttributeUpsertRequestDto patchRequest = textAttribute(attributeKey, "HTTP Attribute Patched " + suffix, "httpField" + suffix);
        mockMvc.perform(patch("/api/meta/attribute-defs/{attrKey}", attributeKey)
                        .param("businessDomain", "MATERIAL")
                        .param("categoryCode", categoryCode)
                        .header("X-User", "patch-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(patchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestVersion.versionNo").value(3))
                .andExpect(jsonPath("$.latestVersion.displayName").value("HTTP Attribute Patched " + suffix))
                .andExpect(jsonPath("$.createdBy").value("http-user"));

        mockMvc.perform(get("/api/meta/attribute-defs/{attrKey}", attributeKey)
                        .param("businessDomain", "MATERIAL")
                        .param("includeValues", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(attributeKey))
                .andExpect(jsonPath("$.businessDomain").value("MATERIAL"))
                .andExpect(jsonPath("$.latestVersion.versionNo").value(3));

        mockMvc.perform(get("/api/meta/attribute-defs/{attrKey}/versions", attributeKey)
                        .param("businessDomain", "MATERIAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNo").value(1))
                .andExpect(jsonPath("$[1].versionNo").value(2))
                .andExpect(jsonPath("$[2].versionNo").value(3));

        mockMvc.perform(delete("/api/meta/attribute-defs/{attrKey}", attributeKey)
                        .param("businessDomain", "MATERIAL")
                        .param("categoryCode", categoryCode)
                        .header("X-User", "delete-user"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/meta/attribute-defs/{attrKey}", attributeKey)
                        .param("businessDomain", "MATERIAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(containsString("属性不存在")));
    }

    @Test
    void listAndDetailEndpoints_shouldHonorBusinessDomainContract() throws Exception {
        String suffix = uniqueSuffix();
        String materialCategoryCode = "MAT-LIST-" + suffix;
        String deviceCategoryCode = "DEV-LIST-" + suffix;
        String materialKey = "ATTR_LIST_M_" + suffix;
        String deviceKey = "ATTR_LIST_D_" + suffix;

                ensureDeviceRuleSet();
        createCategory("MATERIAL", materialCategoryCode, "Material List " + suffix);
        createCategory("DEVICE", deviceCategoryCode, "Device List " + suffix);

        attributeManageService.create("MATERIAL", materialCategoryCode,
                textAttribute(materialKey, "Material List Attribute", "materialListField" + suffix),
                "seed-user");
        attributeManageService.create("DEVICE", deviceCategoryCode,
                textAttribute(deviceKey, "Device List Attribute", "deviceListField" + suffix),
                "seed-user");

        mockMvc.perform(get("/api/meta/attribute-defs")
                        .param("businessDomain", "MATERIAL")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].key").value(materialKey))
                .andExpect(jsonPath("$.content[0].businessDomain").value("MATERIAL"))
                .andExpect(jsonPath("$.content[0].categoryCode").value(materialCategoryCode));

        mockMvc.perform(get("/api/meta/attribute-defs/{attrKey}", materialKey)
                        .param("businessDomain", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(containsString("businessDomain is required")));
    }

    private void createCategory(String businessDomain, String code, String name) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain(businessDomain);
        request.setCode(code);
        request.setName(name);
        categoryCrudService.create(request, "it-user");
    }

    private MetaAttributeUpsertRequestDto textAttribute(String key, String displayName, String attributeField) {
        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setKey(key);
        request.setDisplayName(displayName);
        request.setAttributeField(attributeField);
        request.setDataType("text");
        return request;
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private void ensureDeviceRuleSet() {
        createRuleIfMissing("DEVICE", "CATEGORY_DEVICE", "category", "DEV-{SEQ}", Map.of(
                "pattern", "DEV-{SEQ}",
                "hierarchyMode", "NONE",
                "subRules", Map.of(
                        "category", Map.of(
                                "separator", "-",
                                "segments", List.of(
                                        Map.of("type", "STRING", "value", "DEV"),
                                        Map.of("type", "SEQUENCE", "length", 3, "startValue", 1, "step", 1, "resetRule", "NEVER", "scopeKey", "GLOBAL")
                                ),
                                "allowedVariableKeys", List.of("BUSINESS_DOMAIN", "PARENT_CODE")
                        )
                ),
                "validation", Map.of("maxLength", 128, "regex", "^[A-Z][A-Z0-9_-]{0,127}$", "allowManualOverride", true)
        ));
        createRuleIfMissing("DEVICE", "ATTRIBUTE_DEVICE", "attribute", "DATTR-{CATEGORY_CODE}-{SEQ}", Map.of(
                "pattern", "DATTR-{CATEGORY_CODE}-{SEQ}",
                "hierarchyMode", "NONE",
                "subRules", Map.of(
                        "attribute", Map.of(
                                "separator", "-",
                                "segments", List.of(
                                        Map.of("type", "STRING", "value", "DATTR"),
                                        Map.of("type", "VARIABLE", "variableKey", "CATEGORY_CODE"),
                                        Map.of("type", "SEQUENCE", "length", 3, "startValue", 1, "step", 1, "resetRule", "PER_PARENT", "scopeKey", "CATEGORY_CODE")
                                ),
                                "allowedVariableKeys", List.of("BUSINESS_DOMAIN", "CATEGORY_CODE")
                        )
                ),
                "validation", Map.of("maxLength", 128, "regex", "^[A-Z][A-Z0-9_-]{0,127}$", "allowManualOverride", true)
        ));
        createRuleIfMissing("DEVICE", "LOV_DEVICE", "lov", "DVAL-{ATTRIBUTE_CODE}-{SEQ}", Map.of(
                "pattern", "DVAL-{ATTRIBUTE_CODE}-{SEQ}",
                "hierarchyMode", "NONE",
                "subRules", Map.of(
                        "enum", Map.of(
                                "separator", "-",
                                "segments", List.of(
                                        Map.of("type", "STRING", "value", "DVAL"),
                                        Map.of("type", "VARIABLE", "variableKey", "ATTRIBUTE_CODE"),
                                        Map.of("type", "SEQUENCE", "length", 2, "startValue", 1, "step", 1, "resetRule", "PER_PARENT", "scopeKey", "ATTRIBUTE_CODE")
                                ),
                                "allowedVariableKeys", List.of("BUSINESS_DOMAIN", "CATEGORY_CODE", "ATTRIBUTE_CODE")
                        )
                ),
                "validation", Map.of("maxLength", 128, "regex", "^[A-Z][A-Z0-9_-]{0,127}$", "allowManualOverride", true)
        ));

        CodeRuleSetSaveRequestDto request = new CodeRuleSetSaveRequestDto();
        request.setBusinessDomain("DEVICE");
        request.setName("Device Controller Rule Set");
        request.setRemark("controller-it");
        request.setCategoryRuleCode("CATEGORY_DEVICE");
        request.setAttributeRuleCode("ATTRIBUTE_DEVICE");
        request.setLovRuleCode("LOV_DEVICE");
        codeRuleSetService.create(request, "it-user");
        codeRuleSetService.publish("DEVICE", "it-user");
    }

    private void createRuleIfMissing(String businessDomain,
                                     String ruleCode,
                                     String targetType,
                                     String pattern,
                                     Map<String, Object> ruleJson) {
        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain(businessDomain);
        request.setRuleCode(ruleCode);
        request.setName(ruleCode);
        request.setTargetType(targetType);
        request.setScopeType("GLOBAL");
        request.setPattern(pattern);
        request.setAllowManualOverride(Boolean.TRUE);
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,127}$");
        request.setMaxLength(128);
        request.setRuleJson(ruleJson);
        codeRuleService.create(request, "it-user");
    }
}