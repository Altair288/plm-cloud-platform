package com.plm.attribute.version.service;

import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetDetailDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSummaryDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
class MetaCodeRuleSetServiceIT {

    @Autowired
    private MetaCodeRuleService codeRuleService;

    @Autowired
    private MetaCodeRuleSetService codeRuleSetService;

    @Test
    void detail_shouldReturnSeededMaterialRuleSetWithFullRules() {
        CodeRuleSetDetailDto detail = codeRuleSetService.detail("MATERIAL");

        Assertions.assertEquals("MATERIAL", detail.getBusinessDomain());
        Assertions.assertEquals("CATEGORY", detail.getCategoryRuleCode());
        Assertions.assertEquals("ATTRIBUTE", detail.getAttributeRuleCode());
        Assertions.assertEquals("LOV", detail.getLovRuleCode());
        Assertions.assertEquals(3, detail.getRules().size());
        Assertions.assertTrue(detail.getRules().containsKey("CATEGORY"));
        Assertions.assertTrue(detail.getRules().containsKey("ATTRIBUTE"));
        Assertions.assertTrue(detail.getRules().containsKey("LOV"));
    }

    @Test
    void createAndPublish_shouldActivateBusinessDomainRuleSet() {
        createRule("DEVICE", "CATEGORY_DEVICE", "category", "DEV-{SEQ}", categoryRuleJson());
        createRule("DEVICE", "ATTRIBUTE_DEVICE", "attribute", "DATTR-{CATEGORY_CODE}-{SEQ}", attributeRuleJson());
        createRule("DEVICE", "LOV_DEVICE", "lov", "DVAL-{ATTRIBUTE_CODE}-{SEQ}", lovRuleJson());

        CodeRuleSetSaveRequestDto request = new CodeRuleSetSaveRequestDto();
        request.setBusinessDomain("DEVICE");
        request.setName("Device Rule Set");
        request.setRemark("device-rules");
        request.setCategoryRuleCode("CATEGORY_DEVICE");
        request.setAttributeRuleCode("ATTRIBUTE_DEVICE");
        request.setLovRuleCode("LOV_DEVICE");

        CodeRuleSetDetailDto created = codeRuleSetService.create(request, "it-user");
        Assertions.assertEquals("DRAFT", created.getStatus());
        Assertions.assertFalse(Boolean.TRUE.equals(created.getActive()));

        CodeRuleSetDetailDto published = codeRuleSetService.publish("DEVICE", "it-user");
        Assertions.assertEquals("ACTIVE", published.getStatus());
        Assertions.assertTrue(Boolean.TRUE.equals(published.getActive()));

        CodeRuleDetailDto category = published.getRules().get("CATEGORY");
        Assertions.assertEquals("DEVICE", category.getBusinessDomain());
        Assertions.assertEquals("ACTIVE", category.getStatus());

        List<CodeRuleSetSummaryDto> list = codeRuleSetService.list();
        Assertions.assertTrue(list.stream().anyMatch(item -> "DEVICE".equals(item.getBusinessDomain()) && Boolean.TRUE.equals(item.getActive())));
    }

    private void createRule(String businessDomain,
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
}