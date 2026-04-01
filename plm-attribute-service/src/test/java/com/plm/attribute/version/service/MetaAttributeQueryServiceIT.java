package com.plm.attribute.version.service;

import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeDefListItemDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
class MetaAttributeQueryServiceIT {

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeManageService attributeManageService;

    @Autowired
    private MetaAttributeQueryService queryService;

        @Autowired
        private MetaCodeRuleService codeRuleService;

        @Autowired
        private MetaCodeRuleSetService codeRuleSetService;

    @Test
    void list_shouldFilterByExactCategoryCodeInsteadOfPrefix() {
        String rootCode = "P01" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String childCode = rootCode + "_01";

        MetaCategoryDetailDto root = createCategory("MATERIAL", rootCode, "Root Category", null);
        createCategory("MATERIAL", childCode, "Child Category", root.getId());

        MetaAttributeDefDetailDto rootAttribute = attributeManageService.create("MATERIAL", rootCode,
                attribute("Root Attribute", "rootAttribute"),
                "it-user");
        attributeManageService.create("MATERIAL", childCode,
                attribute("Child Attribute", "childAttribute"),
                "it-user");

        Page<MetaAttributeDefListItemDto> page = queryService.list(
                null,
                rootCode,
                null,
                null,
                null,
                null,
                null,
                false,
                PageRequest.of(0, 20));

        Assertions.assertEquals(1, page.getTotalElements());
        Assertions.assertEquals(List.of(rootAttribute.getKey()),
                page.getContent().stream().map(MetaAttributeDefListItemDto::getKey).toList());
        Assertions.assertTrue(page.getContent().stream().allMatch(item -> rootCode.equals(item.getCategoryCode())));
    }

        @Test
        void detail_shouldRequireBusinessDomainWhenAttrKeyExistsInMultipleDomains() {
                String materialCategoryCode = "MAT-QRY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
                String deviceCategoryCode = "DEV-QRY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
                String sharedAttrKey = "ATTR_SHARED_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();

                ensureDeviceRuleSet();
                createCategory("MATERIAL", materialCategoryCode, "Material Query Category", null);
                createCategory("DEVICE", deviceCategoryCode, "Device Query Category", null);

                MetaAttributeUpsertRequestDto materialRequest = attribute("Material Shared", "materialShared");
                materialRequest.setKey(sharedAttrKey);
                MetaAttributeUpsertRequestDto deviceRequest = attribute("Device Shared", "deviceShared");
                deviceRequest.setKey(sharedAttrKey);

                attributeManageService.create("MATERIAL", materialCategoryCode, materialRequest, "it-user");
                attributeManageService.create("DEVICE", deviceCategoryCode, deviceRequest, "it-user");

                IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                                () -> queryService.detail(sharedAttrKey, true));
                Assertions.assertTrue(exception.getMessage().contains("businessDomain is required"));

                MetaAttributeDefDetailDto materialDetail = queryService.detail("MATERIAL", sharedAttrKey, true);
                MetaAttributeDefDetailDto deviceDetail = queryService.detail("DEVICE", sharedAttrKey, true);

                Assertions.assertEquals("MATERIAL", materialDetail.getBusinessDomain());
                Assertions.assertEquals("DEVICE", deviceDetail.getBusinessDomain());
                Assertions.assertEquals(materialCategoryCode, materialDetail.getCategoryCode());
                Assertions.assertEquals(deviceCategoryCode, deviceDetail.getCategoryCode());
        }

        @Test
        void list_shouldFilterByBusinessDomain() {
                String materialCategoryCode = "MAT-LIST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
                String deviceCategoryCode = "DEV-LIST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();

                ensureDeviceRuleSet();
                createCategory("MATERIAL", materialCategoryCode, "Material List Category", null);
                createCategory("DEVICE", deviceCategoryCode, "Device List Category", null);

                attributeManageService.create("MATERIAL", materialCategoryCode, attribute("Material Attribute", "materialAttr"), "it-user");
                attributeManageService.create("DEVICE", deviceCategoryCode, attribute("Device Attribute", "deviceAttr"), "it-user");

                Page<MetaAttributeDefListItemDto> materialPage = queryService.list(
                                "MATERIAL",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                PageRequest.of(0, 20));

                Assertions.assertFalse(materialPage.isEmpty());
                Assertions.assertTrue(materialPage.getContent().stream().allMatch(item -> "MATERIAL".equals(item.getBusinessDomain())));
        }

        private MetaCategoryDetailDto createCategory(String businessDomain, String code, String name, UUID parentId) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
                request.setBusinessDomain(businessDomain);
        request.setCode(code);
        request.setName(name);
        request.setParentId(parentId);
        return categoryCrudService.create(request, "it-user");
    }

    private MetaAttributeUpsertRequestDto attribute(String displayName, String attributeField) {
        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setDisplayName(displayName);
        request.setAttributeField(attributeField);
        request.setDataType("text");
        return request;
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
        request.setName("Device Query Rule Set");
        request.setRemark("query-it");
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