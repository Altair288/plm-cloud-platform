package com.plm.attribute.version.service;

import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import com.plm.common.version.util.CodeRuleSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
class MetaAttributeManageServiceIT {

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeManageService attributeManageService;

    @Autowired
    private MetaCodeRuleService codeRuleService;

    @Autowired
    private MetaCodeRuleSetService codeRuleSetService;

    @Test
    void createAttribute_shouldAutoGenerateAttributeKeyAndLovKey() {
        String categoryCode = "MAT-ATTR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        createCategory(categoryCode, "Attribute Auto Category");

        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setDisplayName("Color");
        request.setAttributeField("color");
        request.setDataType("enum");
        request.setLovValues(List.of(autoLovValue("Red"), autoLovValue("Blue")));

        MetaAttributeDefDetailDto detail = attributeManageService.create(categoryCode, request, "it-user");

        Assertions.assertNotNull(detail);
        Assertions.assertNotNull(detail.getKey());
        String expectedAttributeKey = "ATTR-" + categoryCode + "-" + String.format("%0" + CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH + "d", 1);
        Assertions.assertEquals(expectedAttributeKey, detail.getKey(), "actual key=" + detail.getKey());
        Assertions.assertNotNull(detail.getLovKey());
        String expectedLovKey = detail.getKey() + "_LOV";
        Assertions.assertEquals(expectedLovKey, detail.getLovKey(),
            "actual key=" + detail.getKey() + ", actual lovKey=" + detail.getLovKey());
        Assertions.assertTrue(Boolean.TRUE.equals(detail.getHasLov()));
        Assertions.assertEquals(2, detail.getLovValues().size());
        Assertions.assertEquals("ENUM-" + detail.getKey() + "-" + String.format("%0" + CodeRuleSupport.LOV_SEQUENCE_WIDTH + "d", 1),
            detail.getLovValues().get(0).getCode());
        Assertions.assertEquals("ENUM-" + detail.getKey() + "-" + String.format("%0" + CodeRuleSupport.LOV_SEQUENCE_WIDTH + "d", 2),
            detail.getLovValues().get(1).getCode());
    }

    @Test
    void createAttribute_shouldResetSequencePerCategory() {
        String categoryA = "MAT-ATTR-A-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String categoryB = "MAT-ATTR-B-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        createCategory(categoryA, "Category A");
        createCategory(categoryB, "Category B");

        MetaAttributeDefDetailDto firstA = attributeManageService.create(categoryA, enumAttribute("Color", "color"), "it-user");
        MetaAttributeDefDetailDto secondA = attributeManageService.create(categoryA, enumAttribute("Size", "size"), "it-user");
        MetaAttributeDefDetailDto firstB = attributeManageService.create(categoryB, enumAttribute("Weight", "weight"), "it-user");

        Assertions.assertEquals(
                "ATTR-" + categoryA + "-" + String.format("%0" + CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH + "d", 1),
                firstA.getKey());
        Assertions.assertEquals(
                "ATTR-" + categoryA + "-" + String.format("%0" + CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH + "d", 2),
                secondA.getKey());
        Assertions.assertEquals(
                "ATTR-" + categoryB + "-" + String.format("%0" + CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH + "d", 1),
                firstB.getKey());
    }

    @Test
    void createAttribute_shouldRespectManualAttributeKeyAndLovKey() {
        String categoryCode = "MAT-ATTR-MANUAL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        createCategory(categoryCode, "Attribute Manual Category");

        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setKey("ATTR_MANUAL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        request.setDisplayName("Status");
        request.setAttributeField("status");
        request.setDataType("enum");
        request.setLovKey(request.getKey() + "_CUSTOM_LOV");
        request.setLovValues(List.of(lovValue("A", "Active"), lovValue("I", "Inactive")));

        MetaAttributeDefDetailDto detail = attributeManageService.create(categoryCode, request, "it-user");

        Assertions.assertEquals(request.getKey(), detail.getKey());
        Assertions.assertEquals(request.getLovKey(), detail.getLovKey());
        Assertions.assertEquals(2, detail.getLovValues().size());
        Assertions.assertEquals("A", detail.getLovValues().get(0).getCode());
        Assertions.assertEquals("I", detail.getLovValues().get(1).getCode());
    }

    @Test
    void updateAttribute_shouldRejectExplicitLovKeyWhenLovGenerationModeIsAuto() {
        String categoryCode = "MAT-ATTR-UPDATE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        createCategory(categoryCode, "Attribute Update Category");

        MetaAttributeUpsertRequestDto createRequest = new MetaAttributeUpsertRequestDto();
        createRequest.setDisplayName("Lifecycle");
        createRequest.setAttributeField("lifecycle");
        createRequest.setDataType("enum");
        createRequest.setLovValues(List.of(lovValue("DRAFT", "Draft"), lovValue("ACTIVE", "Active")));

        MetaAttributeDefDetailDto created = attributeManageService.create(categoryCode, createRequest, "it-user");

        MetaAttributeUpsertRequestDto updateRequest = new MetaAttributeUpsertRequestDto();
        updateRequest.setDisplayName("Lifecycle");
        updateRequest.setAttributeField("lifecycle");
        updateRequest.setDataType("enum");
        updateRequest.setLovGenerationMode("AUTO");
        updateRequest.setLovKey(created.getKey() + "_MANUAL_OVERRIDE");
        updateRequest.setLovValues(List.of(lovValue("DRAFT", "Draft"), lovValue("ACTIVE", "Active")));

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> attributeManageService.update(categoryCode, created.getKey(), updateRequest, "it-user"));
        Assertions.assertTrue(exception.getMessage().contains("lovKey must be empty"));
    }

    @Test
    void createAttribute_shouldUseBusinessDomainRuleSet() {
        ensureDeviceRuleSet();

        CreateCategoryRequestDto categoryRequest = new CreateCategoryRequestDto();
        categoryRequest.setBusinessDomain("DEVICE");
        categoryRequest.setName("Device Category");
        MetaAttributeDefDetailDto detail;

        String categoryCode = categoryCrudService.create(categoryRequest, "it-user").getCode();

        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setDisplayName("Voltage");
        request.setAttributeField("voltage");
        request.setDataType("enum");
        request.setLovValues(List.of(autoLovValue("110V"), autoLovValue("220V")));

        detail = attributeManageService.create(categoryCode, request, "it-user");

        Assertions.assertEquals("DATTR-" + categoryCode + "-001", detail.getKey());
        Assertions.assertEquals(detail.getKey() + "_LOV", detail.getLovKey());
        Assertions.assertEquals("DVAL-" + detail.getKey() + "-01", detail.getLovValues().get(0).getCode());
        Assertions.assertEquals("DVAL-" + detail.getKey() + "-02", detail.getLovValues().get(1).getCode());
    }

    private void createCategory(String code, String name) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setCode(code);
        request.setName(name);
        categoryCrudService.create(request, "it-user");
    }

    private MetaAttributeUpsertRequestDto enumAttribute(String displayName, String attributeField) {
        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setDisplayName(displayName);
        request.setAttributeField(attributeField);
        request.setDataType("enum");
        request.setLovValues(List.of(lovValue("A", "A"), lovValue("B", "B")));
        return request;
    }

    private MetaAttributeUpsertRequestDto.LovValueUpsertItem lovValue(String code, String name) {
        MetaAttributeUpsertRequestDto.LovValueUpsertItem item = new MetaAttributeUpsertRequestDto.LovValueUpsertItem();
        item.setCode(code);
        item.setName(name);
        item.setLabel(name);
        return item;
    }

    private MetaAttributeUpsertRequestDto.LovValueUpsertItem autoLovValue(String name) {
        return lovValue(null, name);
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
        request.setName("Device Rule Set");
        request.setRemark("device-it");
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