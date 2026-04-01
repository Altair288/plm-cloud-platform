package com.plm.attribute.version.service;

import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import com.plm.common.api.dto.attribute.imports.AttributeImportSummaryDto;
import com.plm.common.version.util.CodeRuleSupport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.lazy-initialization=true",
        "spring.main.allow-bean-definition-overriding=true"
    }
)
@ActiveProfiles("dev")
@Transactional
public class MetaAttributeImportServiceIT {

    @Autowired
    private MetaAttributeImportService importService;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeQueryService queryService;

    @Autowired
    private MetaCodeRuleService codeRuleService;

    @Autowired
    private MetaCodeRuleSetService codeRuleSetService;

    @Test
    void testImportIdempotent() throws Exception {
        CreateCategoryRequestDto createCategoryRequest = new CreateCategoryRequestDto();
        createCategoryRequest.setBusinessDomain("MATERIAL");
        createCategoryRequest.setCode("CAT001");
        createCategoryRequest.setName("Import Test Category");
        categoryCrudService.create(createCategoryRequest, "tester");

        // 构造 Excel
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("attr");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("分类编号");
        header.createCell(1).setCellValue("分类名称");
        header.createCell(2).setCellValue("属性名称");
        header.createCell(3).setCellValue("属性类型");
        header.createCell(4).setCellValue("单位");
        header.createCell(5).setCellValue("枚举值1");
        header.createCell(6).setCellValue("枚举值2");
        // 数据行 (假设分类 CAT001 已存在并有版本)
        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("CAT001");
        r1.createCell(2).setCellValue("颜色");
        r1.createCell(3).setCellValue("enum");
        r1.createCell(5).setCellValue("RED");
        r1.createCell(6).setCellValue("BLUE");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();

        MockMultipartFile mf = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());

        AttributeImportSummaryDto first = importService.importExcel("MATERIAL", mf, "tester");
        AttributeImportSummaryDto second = importService.importExcel("MATERIAL", mf, "tester");

        String expectedAttrKey = "ATTR-CAT001-" + String.format("%0" + CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH + "d", 1);
        MetaAttributeDefDetailDto detail = queryService.detail("MATERIAL", expectedAttrKey, true);

        Assertions.assertTrue(first.getCreatedAttributeDefs() >= 0,
            "first summary: createdDefs=" + first.getCreatedAttributeDefs()
                + ", createdAttrVers=" + first.getCreatedAttributeVersions()
                + ", createdLovDefs=" + first.getCreatedLovDefs()
                + ", createdLovVers=" + first.getCreatedLovVersions()
                + ", skipped=" + first.getSkippedUnchanged()
                + ", errors=" + first.getErrorCount());
        Assertions.assertTrue(second.getSkippedUnchanged() >= 1,
            "second summary: createdDefs=" + second.getCreatedAttributeDefs()
                + ", createdAttrVers=" + second.getCreatedAttributeVersions()
                + ", createdLovDefs=" + second.getCreatedLovDefs()
                + ", createdLovVers=" + second.getCreatedLovVersions()
                + ", skipped=" + second.getSkippedUnchanged()
                + ", errors=" + second.getErrorCount());
        Assertions.assertEquals(expectedAttrKey + "_LOV", detail.getLovKey());
        Assertions.assertEquals(2, detail.getLovValues().size());
        Assertions.assertEquals("ENUM-" + expectedAttrKey + "-" + String.format("%0" + CodeRuleSupport.LOV_SEQUENCE_WIDTH + "d", 1),
            detail.getLovValues().get(0).getCode());
        Assertions.assertEquals("ENUM-" + expectedAttrKey + "-" + String.format("%0" + CodeRuleSupport.LOV_SEQUENCE_WIDTH + "d", 2),
            detail.getLovValues().get(1).getCode());
    }

        @Test
        void testImport_shouldUseBusinessDomainRuleSet() throws Exception {
        ensureDeviceRuleSet();

        CreateCategoryRequestDto createCategoryRequest = new CreateCategoryRequestDto();
        createCategoryRequest.setBusinessDomain("DEVICE");
        createCategoryRequest.setName("Device Import Category");
        String categoryCode = categoryCrudService.create(createCategoryRequest, "tester").getCode();

        MockMultipartFile mf = createImportFile(categoryCode, "功率", List.of("LOW", "HIGH"));

        AttributeImportSummaryDto summary = importService.importExcel("DEVICE", mf, "tester");

        Assertions.assertEquals(0, summary.getErrorCount(), "errors=" + summary.getErrors());

        String expectedAttrKey = "DATTR-" + categoryCode + "-001";
        MetaAttributeDefDetailDto detail = queryService.detail("DEVICE", expectedAttrKey, true);
        Assertions.assertEquals(expectedAttrKey + "_LOV", detail.getLovKey());
        Assertions.assertEquals("DVAL-" + expectedAttrKey + "-01", detail.getLovValues().get(0).getCode());
        Assertions.assertEquals("DVAL-" + expectedAttrKey + "-02", detail.getLovValues().get(1).getCode());
        }

        private MockMultipartFile createImportFile(String categoryCode, String attributeName, List<String> values) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("attr");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("分类编号");
        header.createCell(1).setCellValue("分类名称");
        header.createCell(2).setCellValue("属性名称");
        header.createCell(3).setCellValue("属性类型");
        header.createCell(4).setCellValue("单位");
        for (int i = 0; i < values.size(); i++) {
            header.createCell(5 + i).setCellValue("枚举值" + (i + 1));
        }

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(categoryCode);
        row.createCell(2).setCellValue(attributeName);
        row.createCell(3).setCellValue("enum");
        for (int i = 0; i < values.size(); i++) {
            row.createCell(5 + i).setCellValue(values.get(i));
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        return new MockMultipartFile("file", "device.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());
        }

        private void ensureDeviceRuleSet() {
        createRule("DEVICE", "CATEGORY_DEVICE", "category", "DEV-{SEQ}", Map.of(
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
        createRule("DEVICE", "ATTRIBUTE_DEVICE", "attribute", "DATTR-{CATEGORY_CODE}-{SEQ}", Map.of(
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
        createRule("DEVICE", "LOV_DEVICE", "lov", "DVAL-{ATTRIBUTE_CODE}-{SEQ}", Map.of(
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
        codeRuleSetService.create(request, "tester");
        codeRuleSetService.publish("DEVICE", "tester");
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
        codeRuleService.create(request, "tester");
        }
}
