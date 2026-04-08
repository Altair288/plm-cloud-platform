package com.plm.attribute.version.service;

import com.plm.common.api.dto.attribute.CreateAttributeCodePreviewRequestDto;
import com.plm.common.api.dto.attribute.CreateAttributeCodePreviewResponseDto;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.lazy-initialization=true",
        "spring.main.allow-bean-definition-overriding=true"
    }
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

    @Test
    void createAttribute_shouldRejectDuplicateAttributeKeyAcrossCategoriesInSameBusinessDomain() {
        String categoryA = "MAT-DUP-A-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String categoryB = "MAT-DUP-B-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String sharedKey = "ATTR_SHARED_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        createCategory(categoryA, "Duplicate Category A");
        createCategory(categoryB, "Duplicate Category B");

        MetaAttributeUpsertRequestDto first = enumAttribute("Status A", "statusA");
        first.setKey(sharedKey);
        attributeManageService.create("MATERIAL", categoryA, first, "it-user");

        MetaAttributeUpsertRequestDto second = enumAttribute("Status B", "statusB");
        second.setKey(sharedKey);

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> attributeManageService.create("MATERIAL", categoryB, second, "it-user"));
        Assertions.assertTrue(exception.getMessage().contains("attribute already exists"));
    }

    @Test
    void createAttribute_shouldRejectDuplicateEnumCodeAcrossAttributesInSameBusinessDomain() {
        String categoryA = "MAT-ENUM-A-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String categoryB = "MAT-ENUM-B-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String sharedEnumCode = "ENUM_SHARED_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        createCategory(categoryA, "Enum Category A");
        createCategory(categoryB, "Enum Category B");

        MetaAttributeUpsertRequestDto first = new MetaAttributeUpsertRequestDto();
        first.setKey("ATTR_ENUM_A_" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase());
        first.setDisplayName("Enum A");
        first.setAttributeField("enumA");
        first.setDataType("enum");
        first.setLovValues(List.of(lovValue(sharedEnumCode, "A1")));
        attributeManageService.create("MATERIAL", categoryA, first, "it-user");

        MetaAttributeUpsertRequestDto second = new MetaAttributeUpsertRequestDto();
        second.setKey("ATTR_ENUM_B_" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase());
        second.setDisplayName("Enum B");
        second.setAttributeField("enumB");
        second.setDataType("enum");
        second.setLovValues(List.of(lovValue(sharedEnumCode, "B1")));

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> attributeManageService.create("MATERIAL", categoryB, second, "it-user"));
        Assertions.assertTrue(exception.getMessage().contains("enum option code already exists in business domain"));
    }

    @Test
    void updateAttribute_shouldClearStaleNumberFieldsWhenDataTypeChangesToBool() {
        String categoryCode = "MAT-TYPE-SWITCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        createCategory(categoryCode, "Type Switch Category");

        MetaAttributeUpsertRequestDto createRequest = new MetaAttributeUpsertRequestDto();
        createRequest.setDisplayName("主轴功率");
        createRequest.setAttributeField("spindlePower");
        createRequest.setDataType("number");
        createRequest.setUnit("kW");
        createRequest.setDefaultValue("7.5");
        createRequest.setMinValue(new BigDecimal("0"));
        createRequest.setMaxValue(new BigDecimal("99.9"));
        createRequest.setStep(new BigDecimal("0.1"));
        createRequest.setPrecision(1);

        MetaAttributeDefDetailDto created = attributeManageService.create(categoryCode, createRequest, "it-user");

        MetaAttributeUpsertRequestDto updateRequest = new MetaAttributeUpsertRequestDto();
        updateRequest.setDisplayName("主轴功率");
        updateRequest.setAttributeField("spindlePower");
        updateRequest.setDataType("bool");
        updateRequest.setUnit("kW");
        updateRequest.setDefaultValue("7.5");
        updateRequest.setMinValue(new BigDecimal("0"));
        updateRequest.setMaxValue(new BigDecimal("99.9"));
        updateRequest.setStep(new BigDecimal("0.1"));
        updateRequest.setPrecision(1);
        updateRequest.setTrueLabel("是");
        updateRequest.setFalseLabel("否");

        MetaAttributeDefDetailDto updated = attributeManageService.update(categoryCode, created.getKey(), updateRequest, "it-user");

        Assertions.assertNotNull(updated.getLatestVersion());
        Assertions.assertEquals(2, updated.getLatestVersion().getVersionNo());
        Assertions.assertEquals("bool", updated.getLatestVersion().getDataType());
        Assertions.assertNull(updated.getLatestVersion().getUnit());
        Assertions.assertNull(updated.getLatestVersion().getDefaultValue());
        Assertions.assertNull(updated.getLatestVersion().getMinValue());
        Assertions.assertNull(updated.getLatestVersion().getMaxValue());
        Assertions.assertNull(updated.getLatestVersion().getStep());
        Assertions.assertNull(updated.getLatestVersion().getPrecision());
        Assertions.assertEquals("是", updated.getLatestVersion().getTrueLabel());
        Assertions.assertEquals("否", updated.getLatestVersion().getFalseLabel());
    }

        @Test
        void previewCreateCode_shouldReturnSuggestedAttributeAndEnumValueCodes() {
        String categoryCode = "MAT-ATTR-PREVIEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        createCategory(categoryCode, "Preview Category");

        CreateAttributeCodePreviewRequestDto request = new CreateAttributeCodePreviewRequestDto();
        request.setDataType("enum");
        request.setCount(1);
        request.setLovValues(List.of(
            previewLovValue(null, "Red"),
            previewLovValue(null, "Blue")
        ));

        CreateAttributeCodePreviewResponseDto preview = attributeManageService.previewCreateCode(categoryCode, request);

        Assertions.assertEquals("MATERIAL", preview.getBusinessDomain());
        Assertions.assertEquals(categoryCode, preview.getCategoryCode());
        Assertions.assertEquals("AUTO", preview.getGenerationMode());
        Assertions.assertEquals(
            "ATTR-" + categoryCode + "-" + String.format("%0" + CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH + "d", 1),
            preview.getSuggestedCode());
        Assertions.assertNotNull(preview.getLovValuePreviews());
        Assertions.assertEquals(2, preview.getLovValuePreviews().size());
        Assertions.assertEquals(
            "ENUM-" + preview.getSuggestedCode() + "-" + String.format("%0" + CodeRuleSupport.LOV_SEQUENCE_WIDTH + "d", 1),
            preview.getLovValuePreviews().get(0).getSuggestedCode());
        Assertions.assertEquals(
            "ENUM-" + preview.getSuggestedCode() + "-" + String.format("%0" + CodeRuleSupport.LOV_SEQUENCE_WIDTH + "d", 2),
            preview.getLovValuePreviews().get(1).getSuggestedCode());
        Assertions.assertEquals(preview.getSuggestedCode(), preview.getLovResolvedContext().get("ATTRIBUTE_CODE"));
        }

        @Test
        void previewCreateCode_shouldUseManualAttributeKeyForEnumValuePreview() {
        ensureDeviceRuleSet();

        CreateCategoryRequestDto categoryRequest = new CreateCategoryRequestDto();
        categoryRequest.setBusinessDomain("DEVICE");
        categoryRequest.setName("Preview Device Category");
        String categoryCode = categoryCrudService.create(categoryRequest, "it-user").getCode();

        CreateAttributeCodePreviewRequestDto request = new CreateAttributeCodePreviewRequestDto();
        request.setDataType("enum");
        request.setManualKey("DATTR-MANUAL-001");
        request.setLovValues(List.of(
            previewLovValue(null, "110V"),
            previewLovValue(null, "220V")
        ));

        CreateAttributeCodePreviewResponseDto preview = attributeManageService.previewCreateCode(categoryCode, request);

        Assertions.assertEquals("MANUAL", preview.getGenerationMode());
        Assertions.assertEquals("DATTR-MANUAL-001", preview.getSuggestedCode());
        Assertions.assertEquals("DVAL-DATTR-MANUAL-001-01", preview.getLovValuePreviews().get(0).getSuggestedCode());
        Assertions.assertEquals("DVAL-DATTR-MANUAL-001-02", preview.getLovValuePreviews().get(1).getSuggestedCode());
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
        request.setLovValues(List.of(autoLovValue("A"), autoLovValue("B")));
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

    private CreateAttributeCodePreviewRequestDto.LovValuePreviewItem previewLovValue(String code, String name) {
        CreateAttributeCodePreviewRequestDto.LovValuePreviewItem item =
                new CreateAttributeCodePreviewRequestDto.LovValuePreviewItem();
        item.setCode(code);
        item.setName(name);
        item.setLabel(name);
        return item;
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