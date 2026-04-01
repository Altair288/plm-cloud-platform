package com.plm.attribute.version.service;

import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.CreateCategoryCodePreviewRequestDto;
import com.plm.common.api.dto.category.CreateCategoryCodePreviewResponseDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
import com.plm.common.api.dto.code.CodeRulePreviewResponseDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCodeRule;
import com.plm.common.version.util.CodeRuleSupport;
import com.plm.infrastructure.version.repository.MetaCodeRuleRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
class MetaCodeRuleServiceIT {

    @Autowired
    private MetaCodeRuleService codeRuleService;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaCodeRuleRepository codeRuleRepository;

    @Autowired
    private MetaCodeRuleSetService codeRuleSetService;

    @Test
    void codeRuleLifecycle_shouldCreatePreviewAndPublishDraftRule() {
        String ruleCode = "IT_RULE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain("TEST");
        request.setRuleCode(ruleCode);
        request.setName("Integration Test Rule");
        request.setTargetType("category");
        request.setScopeType("GLOBAL");
        request.setPattern("IT-{BUSINESS_DOMAIN}-{SEQ}");
        request.setAllowManualOverride(Boolean.TRUE);
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,63}$");
        request.setMaxLength(64);

        CodeRuleDetailDto created = codeRuleService.create(request, "it-user");
        Assertions.assertEquals(ruleCode, created.getRuleCode());
        Assertions.assertEquals("DRAFT", created.getStatus());
        Assertions.assertEquals(1, created.getLatestVersionNo());

        CodeRulePreviewRequestDto previewRequest = new CodeRulePreviewRequestDto();
        previewRequest.setContext(Map.of("BUSINESS_DOMAIN", "MATERIAL"));
        previewRequest.setCount(2);
        CodeRulePreviewResponseDto preview = codeRuleService.preview(ruleCode, previewRequest);
        Assertions.assertEquals(2, preview.getExamples().size());
        Assertions.assertTrue(preview.getExamples().get(0).startsWith("IT-MATERIAL-"));

        CodeRuleDetailDto published = codeRuleService.publish(ruleCode, "it-user");
        Assertions.assertEquals("ACTIVE", published.getStatus());
        Assertions.assertTrue(Boolean.TRUE.equals(published.getActive()));
    }

    @Test
    void createCategory_shouldAutoGenerateCodeWhenCodeMissing() {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setName("Auto Code Category");
        request.setDescription("auto-code-test");

        MetaCategoryDetailDto created = categoryCrudService.create(request, "it-user");

        Assertions.assertNotNull(created.getId());
        Assertions.assertNotNull(created.getCode());
        Assertions.assertTrue(created.getCode().startsWith("MATERIAL-"));
    }

    @Test
    void createCategory_shouldRespectManualCodeWhenProvided() {
        String manualCode = "MATERIAL-MANUAL-IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setName("Manual Code Category");
        request.setCode(manualCode);

        MetaCategoryDetailDto created = categoryCrudService.create(request, "it-user");

        Assertions.assertEquals(manualCode, created.getCode());
    }

    @Test
    void previewCreateCode_shouldReturnSuggestedCodeForRootCategory() {
        CreateCategoryCodePreviewRequestDto request = new CreateCategoryCodePreviewRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setCount(1);

        CreateCategoryCodePreviewResponseDto preview = categoryCrudService.previewCreateCode(request);

        Assertions.assertEquals("MATERIAL", preview.getBusinessDomain());
        Assertions.assertEquals("CATEGORY", preview.getRuleCode());
        Assertions.assertEquals("AUTO", preview.getGenerationMode());
        Assertions.assertFalse(preview.getExamples().isEmpty());
        Assertions.assertEquals(preview.getExamples().get(0), preview.getSuggestedCode());
        Assertions.assertTrue(preview.getResolvedContext().containsKey("BUSINESS_DOMAIN"));
    }

    @Test
    void previewCreateCode_shouldUseParentCodeForStructuredHierarchyRule() {
        String prefix = "ITP" + suffix().substring(0, 4);
        MetaCodeRule categoryRule = codeRuleRepository.findByCode("CATEGORY").orElseThrow();
        categoryRule.setStatus("DRAFT");
        categoryRule.setActive(Boolean.FALSE);
        codeRuleRepository.save(categoryRule);

        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setRuleCode("CATEGORY");
        request.setName(categoryRule.getName());
        request.setTargetType(categoryRule.getTargetType());
        request.setScopeType(categoryRule.getScopeType());
        request.setPattern(prefix + "-{SEQ}");
        request.setAllowManualOverride(Boolean.TRUE.equals(categoryRule.getAllowManualOverride()));
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,127}$");
        request.setMaxLength(128);
        request.setRuleJson(buildStructuredCategoryRuleJson(prefix));

        codeRuleService.update("CATEGORY", request, "it-user");
        codeRuleService.publish("CATEGORY", "it-user");

        CreateCategoryRequestDto rootRequest = new CreateCategoryRequestDto();
        rootRequest.setBusinessDomain("MATERIAL");
        rootRequest.setName("Preview Root");
        MetaCategoryDetailDto root = categoryCrudService.create(rootRequest, "it-user");

        CreateCategoryCodePreviewRequestDto previewRequest = new CreateCategoryCodePreviewRequestDto();
        previewRequest.setBusinessDomain("MATERIAL");
        previewRequest.setParentId(root.getId());
        previewRequest.setCount(1);

        CreateCategoryCodePreviewResponseDto preview = categoryCrudService.previewCreateCode(previewRequest);

        Assertions.assertEquals(root.getCode() + "-001", preview.getSuggestedCode());
        Assertions.assertEquals(root.getCode(), preview.getResolvedContext().get("PARENT_CODE"));
    }

    @Test
    void codeRuleSupport_shouldExposeCentralizedSequenceWidths() {
        Assertions.assertEquals(CodeRuleSupport.CATEGORY_SEQUENCE_WIDTH, CodeRuleSupport.sequenceWidth("CATEGORY"));
        Assertions.assertEquals(CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH, CodeRuleSupport.sequenceWidth("ATTRIBUTE"));
        Assertions.assertEquals(CodeRuleSupport.LOV_SEQUENCE_WIDTH, CodeRuleSupport.sequenceWidth("LOV"));
        Assertions.assertEquals(CodeRuleSupport.INSTANCE_SEQUENCE_WIDTH, CodeRuleSupport.sequenceWidth("INSTANCE"));
        Assertions.assertEquals(CodeRuleSupport.DEFAULT_SEQUENCE_WIDTH, CodeRuleSupport.sequenceWidth("UNKNOWN"));
    }

    @Test
    void hash_shouldUseMd5ForRuleJsonCompatibility() throws Exception {
        Method hashMethod = MetaCodeRuleService.class.getDeclaredMethod("hash", String.class);
        hashMethod.setAccessible(true);

        String ruleJson = "{\"pattern\":\"ATTR_{SEQ}\"}";
        String hash = (String) hashMethod.invoke(codeRuleService, ruleJson);

        Assertions.assertEquals(CodeRuleSupport.md5Hex(ruleJson), hash);
    }

    @Test
    void builtInRules_shouldExposeStructuredDefaultRuleJson() {
        CodeRuleDetailDto category = codeRuleService.detail("CATEGORY");
        CodeRuleDetailDto attribute = codeRuleService.detail("ATTRIBUTE");
        CodeRuleDetailDto lov = codeRuleService.detail("LOV");

        Assertions.assertEquals("MATERIAL", category.getBusinessDomain());
        Assertions.assertEquals("NONE", String.valueOf(category.getLatestRuleJson().get("hierarchyMode")));
        Assertions.assertFalse(Boolean.TRUE.equals(category.getSupportsHierarchy()));
        Assertions.assertFalse(Boolean.TRUE.equals(category.getSupportsScopedSequence()));
        Assertions.assertTrue(category.getSupportedVariableKeys().contains("BUSINESS_DOMAIN"));
        Assertions.assertTrue(category.getSupportedVariableKeys().contains("PARENT_CODE"));
        Assertions.assertTrue(readObjectMap(category.getLatestRuleJson().get("subRules")).containsKey("category"));
        Assertions.assertTrue(Boolean.TRUE.equals(attribute.getSupportsScopedSequence()));
        Assertions.assertTrue(attribute.getSupportedVariableKeys().contains("CATEGORY_CODE"));
        Assertions.assertTrue(readObjectMap(attribute.getLatestRuleJson().get("subRules")).containsKey("attribute"));
        Assertions.assertTrue(Boolean.TRUE.equals(lov.getSupportsScopedSequence()));
        Assertions.assertTrue(lov.getSupportedVariableKeys().contains("ATTRIBUTE_CODE"));
        Assertions.assertTrue(readObjectMap(lov.getLatestRuleJson().get("subRules")).containsKey("enum"));
    }

        @Test
        void preview_shouldReturnWarningsWhenContextIsMissing() {
        String ruleCode = "IT_WARN_" + suffix();

        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain("TEST");
        request.setRuleCode(ruleCode);
        request.setName("Integration Test Warning Rule");
        request.setTargetType("attribute");
        request.setScopeType("GLOBAL");
        request.setPattern("ATTR-{CATEGORY_CODE}-{SEQ}");
        request.setAllowManualOverride(Boolean.TRUE);
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,127}$");
        request.setMaxLength(128);
        request.setRuleJson(buildStructuredRuleJson(
            "NONE",
            Map.of(
                "attribute",
                subRule(
                    "-",
                    List.of(
                        stringSegment("ATTR"),
                        Map.of("type", "VARIABLE", "variableKey", "CATEGORY_CODE"),
                        sequenceSegment(3, "NEVER", "GLOBAL")
                    ),
                    null,
                    List.of("CATEGORY_CODE")
                )
            )
        ));

        codeRuleService.create(request, "it-user");
        codeRuleService.publish(ruleCode, "it-user");

        CodeRulePreviewRequestDto previewRequest = new CodeRulePreviewRequestDto();
        previewRequest.setCount(2);
        CodeRulePreviewResponseDto preview = codeRuleService.preview(ruleCode, previewRequest);

        Assertions.assertTrue(preview.getExamples().isEmpty());
        Assertions.assertTrue(preview.getWarnings().contains("MISSING_CONTEXT_VARIABLE:CATEGORY_CODE"),
            "actual warnings=" + preview.getWarnings());
        }

    @Test
    void preview_shouldResolvePeriodKeyForStructuredDailyRule() {
        String ruleCode = "IT_DAILY_" + suffix();

        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain("TEST");
        request.setRuleCode(ruleCode);
        request.setName("Integration Test Daily Rule");
        request.setTargetType("category");
        request.setScopeType("GLOBAL");
        request.setPattern("ORD-{DATE}-{SEQ}");
        request.setAllowManualOverride(Boolean.TRUE);
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,127}$");
        request.setMaxLength(128);
        request.setRuleJson(buildDailyStructuredRuleJson("ORD"));

        codeRuleService.create(request, "it-user");
        codeRuleService.publish(ruleCode, "it-user");

        CodeRulePreviewRequestDto previewRequest = new CodeRulePreviewRequestDto();
        previewRequest.setCount(2);
        CodeRulePreviewResponseDto preview = codeRuleService.preview(ruleCode, previewRequest);

        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Assertions.assertEquals(today, preview.getResolvedPeriodKey());
        Assertions.assertNotNull(preview.getResolvedSequenceScope());
        Assertions.assertEquals(2, preview.getExamples().size());
        Assertions.assertEquals("ORD-" + today + "-001", preview.getExamples().get(0));
        Assertions.assertEquals("ORD-" + today + "-002", preview.getExamples().get(1));
    }

        @Test
        void preview_shouldRespectConfiguredSeparatorsIncludingEmptyString() {
        String slashRuleCode = "IT_SEP_" + suffix();

        CodeRuleSaveRequestDto slashRequest = new CodeRuleSaveRequestDto();
        slashRequest.setBusinessDomain("TEST");
        slashRequest.setRuleCode(slashRuleCode);
        slashRequest.setName("Integration Test Slash Separator Rule");
        slashRequest.setTargetType("category");
        slashRequest.setScopeType("GLOBAL");
        slashRequest.setPattern("MAT/{BUSINESS_DOMAIN}/{SEQ}");
        slashRequest.setAllowManualOverride(Boolean.TRUE);
        slashRequest.setRegexPattern("^[A-Z][A-Z0-9_./-]{0,127}$");
        slashRequest.setMaxLength(128);
        slashRequest.setRuleJson(buildStructuredRuleJson(
            "NONE",
            Map.of(
                "category",
                subRule(
                    "/",
                    List.of(
                        stringSegment("MAT"),
                        Map.of("type", "VARIABLE", "variableKey", "BUSINESS_DOMAIN"),
                        sequenceSegment(3, "NEVER", "GLOBAL")
                    ),
                    null,
                    List.of("BUSINESS_DOMAIN")
                )
            )
        ));

        codeRuleService.create(slashRequest, "it-user");
        codeRuleService.publish(slashRuleCode, "it-user");

        CodeRulePreviewRequestDto slashPreviewRequest = new CodeRulePreviewRequestDto();
        slashPreviewRequest.setContext(Map.of("BUSINESS_DOMAIN", "DEVICE"));
        slashPreviewRequest.setCount(1);
        CodeRulePreviewResponseDto slashPreview = codeRuleService.preview(slashRuleCode, slashPreviewRequest);

        Assertions.assertEquals("MAT/DEVICE/001", slashPreview.getExamples().get(0));

        String inlineSymbolRuleCode = "IT_NONESEP_" + suffix();

        CodeRuleSaveRequestDto inlineSymbolRequest = new CodeRuleSaveRequestDto();
        inlineSymbolRequest.setBusinessDomain("TEST");
        inlineSymbolRequest.setRuleCode(inlineSymbolRuleCode);
        inlineSymbolRequest.setName("Integration Test No Separator Rule");
        inlineSymbolRequest.setTargetType("lov");
        inlineSymbolRequest.setScopeType("GLOBAL");
        inlineSymbolRequest.setPattern("LOV-{ATTRIBUTE_CODE}-01");
        inlineSymbolRequest.setAllowManualOverride(Boolean.TRUE);
        inlineSymbolRequest.setRegexPattern("^[A-Z][A-Z0-9_./-]{0,127}$");
        inlineSymbolRequest.setMaxLength(128);
        inlineSymbolRequest.setRuleJson(buildStructuredRuleJson(
            "NONE",
            Map.of(
                "enum",
                subRule(
                    "",
                    List.of(
                        stringSegment("LOV-"),
                        Map.of("type", "VARIABLE", "variableKey", "ATTRIBUTE_CODE"),
                        stringSegment("-"),
                        sequenceSegment(2, "PER_PARENT", "ATTRIBUTE_CODE")
                    ),
                    null,
                    List.of("ATTRIBUTE_CODE")
                )
            )
        ));

        codeRuleService.create(inlineSymbolRequest, "it-user");
        codeRuleService.publish(inlineSymbolRuleCode, "it-user");

        CodeRulePreviewRequestDto inlinePreviewRequest = new CodeRulePreviewRequestDto();
        inlinePreviewRequest.setContext(Map.of("ATTRIBUTE_CODE", "ATTR001"));
        inlinePreviewRequest.setCount(1);
        CodeRulePreviewResponseDto inlinePreview = codeRuleService.preview(inlineSymbolRuleCode, inlinePreviewRequest);

        Assertions.assertEquals("LOV-ATTR001-01", inlinePreview.getExamples().get(0));
        }

    @Test
    void generateCode_shouldUseIndependentSequenceBucketsPerParent() {
        String ruleCode = "IT_PARENT_" + suffix();

        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain("TEST");
        request.setRuleCode(ruleCode);
        request.setName("Integration Test Parent Scope Rule");
        request.setTargetType("category");
        request.setScopeType("GLOBAL");
        request.setPattern("PARENT-{SEQ}");
        request.setAllowManualOverride(Boolean.TRUE);
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,127}$");
        request.setMaxLength(128);
        request.setRuleJson(buildParentScopedRuleJson());

        codeRuleService.create(request, "it-user");
        codeRuleService.publish(ruleCode, "it-user");

        String parentA = "MAT-001";
        String parentB = "MAT-002";
        MetaCodeRuleService.GeneratedCodeResult first = codeRuleService.generateCode(
                ruleCode,
                "CATEGORY",
                null,
                Map.of("PARENT_CODE", parentA),
                null,
                "it-user",
                false
        );
        MetaCodeRuleService.GeneratedCodeResult second = codeRuleService.generateCode(
                ruleCode,
                "CATEGORY",
                null,
                Map.of("PARENT_CODE", parentA),
                null,
                "it-user",
                false
        );
        MetaCodeRuleService.GeneratedCodeResult third = codeRuleService.generateCode(
                ruleCode,
                "CATEGORY",
                null,
                Map.of("PARENT_CODE", parentB),
                null,
                "it-user",
                false
        );

        Assertions.assertEquals(parentA + "-001", first.code());
        Assertions.assertEquals(parentA + "-002", second.code());
        Assertions.assertEquals(parentB + "-001", third.code());
    }

    @Test
    void createCategory_shouldUseParentCodeForStructuredHierarchyRule() {
        String prefix = "ITC" + suffix().substring(0, 4);
        MetaCodeRule categoryRule = codeRuleRepository.findByCode("CATEGORY").orElseThrow();
        categoryRule.setStatus("DRAFT");
        categoryRule.setActive(Boolean.FALSE);
        codeRuleRepository.save(categoryRule);

        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setRuleCode("CATEGORY");
        request.setName(categoryRule.getName());
        request.setTargetType(categoryRule.getTargetType());
        request.setScopeType(categoryRule.getScopeType());
        request.setPattern(prefix + "-{SEQ}");
        request.setAllowManualOverride(Boolean.TRUE.equals(categoryRule.getAllowManualOverride()));
        request.setRegexPattern("^[A-Z][A-Z0-9_-]{0,127}$");
        request.setMaxLength(128);
        request.setRuleJson(buildStructuredCategoryRuleJson(prefix));

        codeRuleService.update("CATEGORY", request, "it-user");
        codeRuleService.publish("CATEGORY", "it-user");

        CreateCategoryRequestDto rootOneRequest = new CreateCategoryRequestDto();
        rootOneRequest.setBusinessDomain("MATERIAL");
        rootOneRequest.setName("Structured Root One");
        MetaCategoryDetailDto rootOne = categoryCrudService.create(rootOneRequest, "it-user");

        CreateCategoryRequestDto rootTwoRequest = new CreateCategoryRequestDto();
        rootTwoRequest.setBusinessDomain("MATERIAL");
        rootTwoRequest.setName("Structured Root Two");
        MetaCategoryDetailDto rootTwo = categoryCrudService.create(rootTwoRequest, "it-user");

        CreateCategoryRequestDto childOneRequest = new CreateCategoryRequestDto();
        childOneRequest.setBusinessDomain("MATERIAL");
        childOneRequest.setName("Structured Child One");
        childOneRequest.setParentId(rootOne.getId());
        MetaCategoryDetailDto childOne = categoryCrudService.create(childOneRequest, "it-user");

        CreateCategoryRequestDto childTwoRequest = new CreateCategoryRequestDto();
        childTwoRequest.setBusinessDomain("MATERIAL");
        childTwoRequest.setName("Structured Child Two");
        childTwoRequest.setParentId(rootOne.getId());
        MetaCategoryDetailDto childTwo = categoryCrudService.create(childTwoRequest, "it-user");

        CreateCategoryRequestDto childThreeRequest = new CreateCategoryRequestDto();
        childThreeRequest.setBusinessDomain("MATERIAL");
        childThreeRequest.setName("Structured Child Three");
        childThreeRequest.setParentId(rootTwo.getId());
        MetaCategoryDetailDto childThree = categoryCrudService.create(childThreeRequest, "it-user");

        Assertions.assertTrue(rootOne.getCode().startsWith(prefix + "-"), "actual rootOne code=" + rootOne.getCode());
        Assertions.assertTrue(rootTwo.getCode().startsWith(prefix + "-"), "actual rootTwo code=" + rootTwo.getCode());
        Assertions.assertEquals(rootOne.getCode() + "-001", childOne.getCode());
        Assertions.assertEquals(rootOne.getCode() + "-002", childTwo.getCode());
        Assertions.assertEquals(rootTwo.getCode() + "-001", childThree.getCode());
    }

        @Test
        void createCategory_shouldUseBusinessDomainRuleSet() {
        createRule("DEVICE", "CATEGORY_DEVICE", "category", "DEV-{SEQ}", buildDeviceCategoryRuleJson());
        createRule("DEVICE", "ATTRIBUTE_DEVICE", "attribute", "DATTR-{CATEGORY_CODE}-{SEQ}", buildDeviceAttributeRuleJson());
        createRule("DEVICE", "LOV_DEVICE", "lov", "DVAL-{ATTRIBUTE_CODE}-{SEQ}", buildDeviceLovRuleJson());

        CodeRuleSetSaveRequestDto ruleSetRequest = new CodeRuleSetSaveRequestDto();
        ruleSetRequest.setBusinessDomain("DEVICE");
        ruleSetRequest.setName("Device Rule Set");
        ruleSetRequest.setRemark("device-it");
        ruleSetRequest.setCategoryRuleCode("CATEGORY_DEVICE");
        ruleSetRequest.setAttributeRuleCode("ATTRIBUTE_DEVICE");
        ruleSetRequest.setLovRuleCode("LOV_DEVICE");
        codeRuleSetService.create(ruleSetRequest, "it-user");
        codeRuleSetService.publish("DEVICE", "it-user");

        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("DEVICE");
        request.setName("Device Auto Category");

        MetaCategoryDetailDto created = categoryCrudService.create(request, "it-user");

        Assertions.assertTrue(created.getCode().startsWith("DEV-"), "actual code=" + created.getCode());
        }

        @Test
        void createCategory_shouldFailWhenRuleSetMissing() {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("DOCUMENT");
        request.setName("Document Auto Category");

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
            () -> categoryCrudService.create(request, "it-user"));

        Assertions.assertTrue(exception.getMessage().contains("CODE_RULE_SET_NOT_CONFIGURED"), exception.getMessage());
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

        private Map<String, Object> buildDeviceCategoryRuleJson() {
        return buildStructuredRuleJson(
            "NONE",
            Map.of(
                "category",
                subRule(
                    "-",
                    List.of(stringSegment("DEV"), sequenceSegment(3, "NEVER", "GLOBAL")),
                    null,
                    List.of("BUSINESS_DOMAIN", "PARENT_CODE")
                )
            )
        );
        }

        private Map<String, Object> buildDeviceAttributeRuleJson() {
        return buildStructuredRuleJson(
            "NONE",
            Map.of(
                "attribute",
                subRule(
                    "-",
                    List.of(stringSegment("DATTR"), Map.of("type", "VARIABLE", "variableKey", "CATEGORY_CODE"), sequenceSegment(3, "PER_PARENT", "CATEGORY_CODE")),
                    null,
                    List.of("BUSINESS_DOMAIN", "CATEGORY_CODE")
                )
            )
        );
        }

        private Map<String, Object> buildDeviceLovRuleJson() {
        return buildStructuredRuleJson(
            "NONE",
            Map.of(
                "enum",
                subRule(
                    "-",
                    List.of(stringSegment("DVAL"), Map.of("type", "VARIABLE", "variableKey", "ATTRIBUTE_CODE"), sequenceSegment(2, "PER_PARENT", "ATTRIBUTE_CODE")),
                    null,
                    List.of("BUSINESS_DOMAIN", "CATEGORY_CODE", "ATTRIBUTE_CODE")
                )
            )
        );
        }

    private Map<String, Object> buildDailyStructuredRuleJson(String prefix) {
        return buildStructuredRuleJson(
                "NONE",
                Map.of(
                        "category",
                        subRule(
                                "-",
                                List.of(
                                        stringSegment(prefix),
                                        dateSegment("yyyyMMdd"),
                                        sequenceSegment(3, "DAILY", "GLOBAL")
                                ),
                                null,
                                List.of("BUSINESS_DOMAIN")
                        )
                )
        );
    }

    private Map<String, Object> buildParentScopedRuleJson() {
        return buildStructuredRuleJson(
                "APPEND_CHILD_SUFFIX",
                Map.of(
                        "category",
                        subRule(
                                "-",
                                List.of(stringSegment("ROOT"), sequenceSegment(3, "NEVER", "GLOBAL")),
                                List.of(sequenceSegment(3, "PER_PARENT", "PARENT_CODE")),
                                List.of("PARENT_CODE")
                        )
                )
        );
    }

    private Map<String, Object> buildStructuredCategoryRuleJson(String prefix) {
        return buildStructuredRuleJson(
                "APPEND_CHILD_SUFFIX",
                Map.of(
                        "category",
                        subRule(
                                "-",
                                List.of(stringSegment(prefix), sequenceSegment(3, "NEVER", "GLOBAL")),
                                List.of(sequenceSegment(3, "PER_PARENT", "PARENT_CODE")),
                                List.of("BUSINESS_DOMAIN", "PARENT_CODE")
                        )
                )
        );
    }

    private Map<String, Object> buildStructuredRuleJson(String hierarchyMode, Map<String, Object> subRules) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("pattern", "STRUCTURED");
        root.put("hierarchyMode", hierarchyMode);
        root.put("subRules", subRules);
        root.put("validation", Map.of(
                "maxLength", 128,
                "regex", "^[A-Z][A-Z0-9_-]{0,127}$",
                "allowManualOverride", true
        ));
        return root;
    }

    private Map<String, Object> subRule(String separator,
                                        List<Map<String, Object>> segments,
                                        List<Map<String, Object>> childSegments,
                                        List<String> allowedVariableKeys) {
        LinkedHashMap<String, Object> subRule = new LinkedHashMap<>();
        subRule.put("separator", separator);
        subRule.put("segments", segments);
        if (childSegments != null) {
            subRule.put("childSegments", childSegments);
        }
        subRule.put("allowedVariableKeys", allowedVariableKeys);
        return subRule;
    }

    private Map<String, Object> stringSegment(String value) {
        return Map.of("type", "STRING", "value", value);
    }

    private Map<String, Object> dateSegment(String format) {
        return Map.of("type", "DATE", "format", format);
    }

    private Map<String, Object> sequenceSegment(int length, String resetRule, String scopeKey) {
        LinkedHashMap<String, Object> segment = new LinkedHashMap<>();
        segment.put("type", "SEQUENCE");
        segment.put("length", length);
        segment.put("startValue", 1);
        segment.put("step", 1);
        segment.put("resetRule", resetRule);
        segment.put("scopeKey", scopeKey);
        return segment;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        return (Map<String, Object>) rawMap;
    }
}