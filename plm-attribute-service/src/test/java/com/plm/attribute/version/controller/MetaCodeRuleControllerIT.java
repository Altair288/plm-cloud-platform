package com.plm.attribute.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class MetaCodeRuleControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void codeRuleEndpoints_shouldSupportLifecycle() throws Exception {
        String suffix = uniqueSuffix();
        String ruleCode = "IT_RULE_" + suffix;

        mockMvc.perform(post("/api/meta/code-rules")
                        .param("operator", "rule-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(categoryRuleRequest("TEST", ruleCode, "IT-{BUSINESS_DOMAIN}-{SEQ}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value(ruleCode))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.latestVersionNo").value(1));

        mockMvc.perform(get("/api/meta/code-rules/{ruleCode}", ruleCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDomain").value("TEST"))
                .andExpect(jsonPath("$.ruleCode").value(ruleCode));

        CodeRuleSaveRequestDto updateRequest = categoryRuleRequest("TEST", ruleCode, "UPD-{BUSINESS_DOMAIN}-{SEQ}");
        updateRequest.setName("Updated Rule " + suffix);

        mockMvc.perform(put("/api/meta/code-rules/{ruleCode}", ruleCode)
                        .param("operator", "rule-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pattern").value("UPD-{BUSINESS_DOMAIN}-{SEQ}"))
                .andExpect(jsonPath("$.name").value("Updated Rule " + suffix));

        CodeRulePreviewRequestDto previewRequest = new CodeRulePreviewRequestDto();
        previewRequest.setContext(Map.of("BUSINESS_DOMAIN", "MATERIAL"));
        previewRequest.setCount(2);

        mockMvc.perform(post("/api/meta/code-rules/{ruleCode}:preview", ruleCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(previewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value(ruleCode))
                .andExpect(jsonPath("$.examples.length()").value(2))
                .andExpect(jsonPath("$.examples[0]").value(containsString("UPD-MATERIAL-")));

        mockMvc.perform(post("/api/meta/code-rules/{ruleCode}:publish", ruleCode)
                        .param("operator", "rule-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/meta/code-rules")
                        .param("businessDomain", "TEST")
                        .param("targetType", "category")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleCode").value(ruleCode));
    }

    @Test
    void codeRuleSetEndpoints_shouldSupportLifecycle() throws Exception {
        String suffix = uniqueSuffix();
        String categoryRuleCode = "CATEGORY_DEVICE_" + suffix;
        String attributeRuleCode = "ATTRIBUTE_DEVICE_" + suffix;
        String lovRuleCode = "LOV_DEVICE_" + suffix;

        createRuleThroughController("DEVICE", categoryRuleCode, "category", "DEV-{SEQ}", categoryRuleJson());
        createRuleThroughController("DEVICE", attributeRuleCode, "attribute", "DATTR-{CATEGORY_CODE}-{SEQ}", attributeRuleJson());
        createRuleThroughController("DEVICE", lovRuleCode, "lov", "DVAL-{ATTRIBUTE_CODE}-{SEQ}", lovRuleJson());

        CodeRuleSetSaveRequestDto createRequest = new CodeRuleSetSaveRequestDto();
        createRequest.setBusinessDomain("DEVICE");
        createRequest.setName("Device Rule Set " + suffix);
        createRequest.setRemark("controller-it");
        createRequest.setCategoryRuleCode(categoryRuleCode);
        createRequest.setAttributeRuleCode(attributeRuleCode);
        createRequest.setLovRuleCode(lovRuleCode);

        mockMvc.perform(post("/api/meta/code-rule-sets")
                        .param("operator", "ruleset-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDomain").value("DEVICE"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.categoryRuleCode").value(categoryRuleCode));

        mockMvc.perform(get("/api/meta/code-rule-sets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.businessDomain == 'DEVICE')]").exists());

        mockMvc.perform(get("/api/meta/code-rule-sets/{businessDomain}", "DEVICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDomain").value("DEVICE"))
                .andExpect(jsonPath("$.rules.CATEGORY.ruleCode").value(categoryRuleCode))
                .andExpect(jsonPath("$.rules.ATTRIBUTE.ruleCode").value(attributeRuleCode))
                .andExpect(jsonPath("$.rules.LOV.ruleCode").value(lovRuleCode));

        CodeRuleSetSaveRequestDto updateRequest = new CodeRuleSetSaveRequestDto();
        updateRequest.setBusinessDomain("DEVICE");
        updateRequest.setName("Device Rule Set Updated " + suffix);
        updateRequest.setRemark("controller-it-updated");
        updateRequest.setCategoryRuleCode(categoryRuleCode);
        updateRequest.setAttributeRuleCode(attributeRuleCode);
        updateRequest.setLovRuleCode(lovRuleCode);

        mockMvc.perform(put("/api/meta/code-rule-sets/{businessDomain}", "DEVICE")
                        .param("operator", "ruleset-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Device Rule Set Updated " + suffix))
                .andExpect(jsonPath("$.remark").value("controller-it-updated"));

        mockMvc.perform(post("/api/meta/code-rule-sets/{businessDomain}:publish", "DEVICE")
                        .param("operator", "ruleset-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true));
    }

    private void createRuleThroughController(String businessDomain,
                                             String ruleCode,
                                             String targetType,
                                             String pattern,
                                             Map<String, Object> ruleJson) throws Exception {
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

        mockMvc.perform(post("/api/meta/code-rules")
                        .param("operator", "ruleset-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value(ruleCode));
    }

    private CodeRuleSaveRequestDto categoryRuleRequest(String businessDomain, String ruleCode, String pattern) {
        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain(businessDomain);
        request.setRuleCode(ruleCode);
        request.setName("Rule " + ruleCode);
        request.setTargetType("category");
        request.setScopeType("GLOBAL");
        request.setPattern(pattern);
        request.setAllowManualOverride(Boolean.TRUE);
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,127}$");
        request.setMaxLength(128);
        request.setRuleJson(Map.of(
                "pattern", pattern,
                "hierarchyMode", "NONE",
                "subRules", Map.of(
                        "category", Map.of(
                                "separator", "-",
                                "segments", List.of(
                                        Map.of("type", "STRING", "value", pattern.startsWith("UPD") ? "UPD" : "IT"),
                                        Map.of("type", "VARIABLE", "variableKey", "BUSINESS_DOMAIN"),
                                        Map.of("type", "SEQUENCE", "length", 3, "startValue", 1, "step", 1, "resetRule", "NEVER", "scopeKey", "GLOBAL")
                                ),
                                "allowedVariableKeys", List.of("BUSINESS_DOMAIN")
                        )
                ),
                "validation", Map.of("maxLength", 128, "regex", "^[A-Z][A-Z0-9_-]{0,127}$", "allowManualOverride", true)
        ));
        return request;
    }

    private Map<String, Object> categoryRuleJson() {
        return Map.of(
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
        );
    }

    private Map<String, Object> attributeRuleJson() {
        return Map.of(
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
        );
    }

    private Map<String, Object> lovRuleJson() {
        return Map.of(
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
        );
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}