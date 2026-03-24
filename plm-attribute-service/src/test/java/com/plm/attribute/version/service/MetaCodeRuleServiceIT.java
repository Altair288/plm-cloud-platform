package com.plm.attribute.version.service;

import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
import com.plm.common.api.dto.code.CodeRulePreviewResponseDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.version.util.CodeRuleSupport;
import com.plm.common.version.domain.MetaCategoryDef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
class MetaCodeRuleServiceIT {

    @Autowired
    private MetaCodeRuleService codeRuleService;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Test
    void codeRuleLifecycle_shouldCreatePreviewAndPublishDraftRule() {
        String ruleCode = "IT_RULE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        CodeRuleSaveRequestDto request = new CodeRuleSaveRequestDto();
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
}