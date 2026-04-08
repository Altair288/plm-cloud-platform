package com.plm.attribute.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.attribute.version.service.MetaAttributeManageService;
import com.plm.attribute.version.service.MetaCategoryCrudService;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobResultDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobStatusDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportLogEventDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportLogPageDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportPlanResponseDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartRequestDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartResponseDto;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
@Import(WorkbookExportControllerIT.SyncAsyncExecutorConfig.class)
class WorkbookExportControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeManageService attributeManageService;

    @Test
    void schema_shouldExposeDatabaseAlignedAttributeParameterFields() throws Exception {
        mockMvc.perform(get("/api/meta/exports/workbook/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[?(@.moduleKey=='ATTRIBUTE')].fields[?(@.fieldKey=='minValue')]").isNotEmpty())
                .andExpect(jsonPath("$.modules[?(@.moduleKey=='ATTRIBUTE')].fields[?(@.fieldKey=='precision')]").isNotEmpty())
                .andExpect(jsonPath("$.modules[?(@.moduleKey=='ATTRIBUTE')].fields[?(@.fieldKey=='trueLabel')]").isNotEmpty())
                .andExpect(jsonPath("$.modules[?(@.moduleKey=='ATTRIBUTE')].fields[?(@.fieldKey=='falseLabel')]").isNotEmpty())
                .andExpect(jsonPath("$.modules[?(@.moduleKey=='ENUM_OPTION')].fields[?(@.fieldKey=='optionLabel')]").isNotEmpty());
    }

    @Test
    void exportJobEndpoints_shouldGenerateWorkbookWithCategoryAttributeAndEnumSheets() throws Exception {
        String suffix = uniqueSuffix();
        SeededMetadata seeded = seedExportMetadata(suffix);

        WorkbookExportStartRequestDto request = buildExportRequest(seeded, suffix);
        MvcResult startResult = mockMvc.perform(post("/api/meta/exports/workbook/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn();
        WorkbookExportStartResponseDto start = readValue(startResult, WorkbookExportStartResponseDto.class);

        MvcResult statusResult = mockMvc.perform(get("/api/meta/exports/workbook/jobs/{jobId}", start.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progress.categories.total").value(2))
                .andExpect(jsonPath("$.progress.attributes.total").value(2))
                .andExpect(jsonPath("$.progress.enumOptions.total").value(2))
                .andReturn();
        WorkbookExportJobStatusDto jobStatus = readValue(statusResult, WorkbookExportJobStatusDto.class);
        Assertions.assertEquals(100, jobStatus.getOverallPercent());

        MvcResult logResult = mockMvc.perform(get("/api/meta/exports/workbook/jobs/{jobId}/logs", start.getJobId())
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").isNotEmpty())
                .andReturn();
        WorkbookExportLogPageDto logPage = readValue(logResult, WorkbookExportLogPageDto.class);
        Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_EXPORT_SCOPE_DONE"));
        Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_EXPORT_DATA_DONE"));
        Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_EXPORT_COMPLETED"));

        MvcResult resultResult = mockMvc.perform(get("/api/meta/exports/workbook/jobs/{jobId}/result", start.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file.fileName").value("material-export-" + suffix + ".xlsx"))
                .andExpect(jsonPath("$.summary.categories.totalRows").value(2))
                .andExpect(jsonPath("$.summary.attributes.totalRows").value(2))
                .andExpect(jsonPath("$.summary.enumOptions.totalRows").value(2))
                .andReturn();
        WorkbookExportJobResultDto result = readValue(resultResult, WorkbookExportJobResultDto.class);
        Assertions.assertNotNull(result.getFile());
        Assertions.assertNotNull(result.getFile().getChecksum());

        MvcResult downloadResult = mockMvc.perform(get("/api/meta/exports/workbook/jobs/{jobId}/download", start.getJobId()))
                .andExpect(status().isOk())
                .andReturn();

        assertWorkbook(downloadResult.getResponse().getContentAsByteArray(), seeded);

        MvcResult streamResult = mockMvc.perform(get("/api/meta/exports/workbook/jobs/{jobId}/stream", start.getJobId())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        Objects.requireNonNull(streamResult.getRequest().getAsyncContext(), "asyncContext").complete();
    }

        @Test
        void planEndpoint_shouldReturnNormalizedRequestAndEstimatedRows() throws Exception {
                String suffix = uniqueSuffix();
                SeededMetadata seeded = seedExportMetadata(suffix);

                WorkbookExportStartRequestDto request = buildExportRequest(seeded, suffix);
                MvcResult result = mockMvc.perform(post("/api/meta/exports/workbook/plan")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.normalizedRequest.businessDomain").value("MATERIAL"))
                                .andExpect(jsonPath("$.estimate.categoryRows").value(2))
                                .andExpect(jsonPath("$.estimate.attributeRows").value(2))
                                .andExpect(jsonPath("$.estimate.enumOptionRows").value(2))
                                .andReturn();

                WorkbookExportPlanResponseDto plan = readValue(result, WorkbookExportPlanResponseDto.class);
                Assertions.assertEquals("XLSX", plan.getNormalizedRequest().getOutput().getFormat());
                Assertions.assertTrue(plan.getWarnings().isEmpty());
        }

    private SeededMetadata seedExportMetadata(String suffix) {
        MetaCategoryDetailDto root = categoryCrudService.create(categoryRequest("ROOT-" + suffix, "Root " + suffix, null), "it-user");
        MetaCategoryDetailDto child = categoryCrudService.create(categoryRequest("CHILD-" + suffix, "Child " + suffix, root.getId()), "it-user");

        MetaAttributeUpsertRequestDto numberAttribute = new MetaAttributeUpsertRequestDto();
        numberAttribute.setKey("WEIGHT-" + suffix);
        numberAttribute.setGenerationMode("MANUAL");
        numberAttribute.setDisplayName("Weight " + suffix);
        numberAttribute.setAttributeField("weight" + suffix.toLowerCase());
        numberAttribute.setDescription("Weight attribute " + suffix);
        numberAttribute.setDataType("number");
        numberAttribute.setUnit("kg");
        numberAttribute.setDefaultValue("1.5");
        numberAttribute.setRequired(true);
        numberAttribute.setUnique(false);
        numberAttribute.setHidden(false);
        numberAttribute.setReadOnly(false);
        numberAttribute.setSearchable(true);
        numberAttribute.setMinValue(new BigDecimal("0.1"));
        numberAttribute.setMaxValue(new BigDecimal("9.9"));
        numberAttribute.setStep(new BigDecimal("0.1"));
        numberAttribute.setPrecision(2);
        attributeManageService.create("MATERIAL", child.getCode(), numberAttribute, "it-user");

        MetaAttributeUpsertRequestDto enumAttribute = new MetaAttributeUpsertRequestDto();
        enumAttribute.setKey("COLOR-" + suffix);
        enumAttribute.setGenerationMode("MANUAL");
        enumAttribute.setDisplayName("Color " + suffix);
        enumAttribute.setAttributeField("color" + suffix.toLowerCase());
        enumAttribute.setDescription("Color attribute " + suffix);
        enumAttribute.setDataType("enum");
        enumAttribute.setReadOnly(false);
        enumAttribute.setHidden(false);
        enumAttribute.setRequired(false);
        enumAttribute.setUnique(false);
        enumAttribute.setSearchable(true);
        enumAttribute.setLovKey("COLOR-LOV-" + suffix);
        enumAttribute.setLovGenerationMode("MANUAL");
        enumAttribute.setFreezeLovKey(false);
        enumAttribute.setLovValues(List.of(lovValue("RED-" + suffix, "Red " + suffix, "Red Label " + suffix), lovValue("BLUE-" + suffix, "Blue " + suffix, "Blue Label " + suffix)));
        attributeManageService.create("MATERIAL", child.getCode(), enumAttribute, "it-user");

        return new SeededMetadata(root.getId(), root.getCode(), child.getCode(), "WEIGHT-" + suffix, "COLOR-" + suffix, "COLOR-LOV-" + suffix);
    }

    private CreateCategoryRequestDto categoryRequest(String code,
                                                     String name,
                                                     UUID parentId) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setCode(code);
        request.setGenerationMode("MANUAL");
        request.setName(name);
        request.setStatus("active");
        request.setDescription(name + " description");
        request.setParentId(parentId);
        request.setSort(1);
        return request;
    }

    private MetaAttributeUpsertRequestDto.LovValueUpsertItem lovValue(String code,
                                                                      String name,
                                                                      String label) {
        MetaAttributeUpsertRequestDto.LovValueUpsertItem item = new MetaAttributeUpsertRequestDto.LovValueUpsertItem();
        item.setCode(code);
        item.setName(name);
        item.setLabel(label);
        return item;
    }

    private WorkbookExportStartRequestDto buildExportRequest(SeededMetadata seeded,
                                                             String suffix) {
        WorkbookExportStartRequestDto request = new WorkbookExportStartRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setOperator("it-user");
        request.setClientRequestId(UUID.randomUUID().toString());

        WorkbookExportStartRequestDto.ScopeDto scope = new WorkbookExportStartRequestDto.ScopeDto();
        scope.setCategoryIds(List.of(seeded.rootId()));
        scope.setIncludeChildren(true);
        request.setScope(scope);

        WorkbookExportStartRequestDto.OutputDto output = new WorkbookExportStartRequestDto.OutputDto();
        output.setFormat("XLSX");
        output.setFileName("material-export-" + suffix + ".xlsx");
        output.setPathSeparator(" > ");
        request.setOutput(output);

        request.setModules(List.of(
                module("CATEGORY", "分类导出", List.of(
                        column("categoryCode", "分类编码"),
                        column("categoryName", "分类名称"),
                        column("parentCode", "父级编码")
                )),
                module("ATTRIBUTE", "属性导出", List.of(
                        column("categoryCode", "分类编码"),
                        column("attributeKey", "属性编码"),
                        column("dataType", "数据类型"),
                        column("unit", "单位"),
                        column("defaultValue", "默认值"),
                        column("required", "必填"),
                        column("searchable", "可搜索"),
                        column("minValue", "最小值"),
                        column("maxValue", "最大值"),
                        column("step", "步长"),
                        column("precision", "精度"),
                        column("lovKey", "LOV编码")
                )),
                module("ENUM_OPTION", "枚举导出", List.of(
                        column("attributeKey", "属性编码"),
                        column("lovKey", "LOV编码"),
                        column("optionCode", "枚举编码"),
                        column("optionName", "枚举值"),
                        column("optionLabel", "显示标签")
                ))
        ));
        return request;
    }

    private WorkbookExportStartRequestDto.ModuleRequestDto module(String moduleKey,
                                                                  String sheetName,
                                                                  List<com.plm.common.api.dto.exports.workbook.WorkbookExportColumnRequestDto> columns) {
        WorkbookExportStartRequestDto.ModuleRequestDto module = new WorkbookExportStartRequestDto.ModuleRequestDto();
        module.setModuleKey(moduleKey);
        module.setEnabled(true);
        module.setSheetName(sheetName);
        module.setColumns(columns);
        return module;
    }

    private com.plm.common.api.dto.exports.workbook.WorkbookExportColumnRequestDto column(String fieldKey,
                                                                                           String headerText) {
        com.plm.common.api.dto.exports.workbook.WorkbookExportColumnRequestDto column = new com.plm.common.api.dto.exports.workbook.WorkbookExportColumnRequestDto();
        column.setFieldKey(fieldKey);
        column.setHeaderText(headerText);
        column.setClientColumnId(fieldKey);
        return column;
    }

    private void assertWorkbook(byte[] bytes,
                                SeededMetadata seeded) throws Exception {
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Assertions.assertEquals(List.of("分类导出", "属性导出", "枚举导出"), List.of(
                    workbook.getSheetAt(0).getSheetName(),
                    workbook.getSheetAt(1).getSheetName(),
                    workbook.getSheetAt(2).getSheetName()));

            Assertions.assertEquals(seeded.rootCode(), formatter.formatCellValue(workbook.getSheet("分类导出").getRow(1).getCell(0)));
            Assertions.assertEquals(seeded.childCode(), formatter.formatCellValue(workbook.getSheet("分类导出").getRow(2).getCell(0)));

            Map<String, List<String>> attributeRows = Map.of(
                    formatter.formatCellValue(workbook.getSheet("属性导出").getRow(1).getCell(1)), rowValues(workbook, "属性导出", 1, formatter),
                    formatter.formatCellValue(workbook.getSheet("属性导出").getRow(2).getCell(1)), rowValues(workbook, "属性导出", 2, formatter)
            );
            Assertions.assertTrue(attributeRows.containsKey(seeded.numberAttributeKey()));
            Assertions.assertTrue(attributeRows.containsKey(seeded.enumAttributeKey()));
            Assertions.assertTrue(attributeRows.get(seeded.numberAttributeKey()).contains("0.1"));
            Assertions.assertTrue(attributeRows.get(seeded.numberAttributeKey()).contains("2"));
            Assertions.assertTrue(attributeRows.get(seeded.enumAttributeKey()).contains(seeded.lovKey()));

            Assertions.assertEquals("RED-" + seeded.numberAttributeKey().substring(seeded.numberAttributeKey().indexOf('-') + 1), formatter.formatCellValue(workbook.getSheet("枚举导出").getRow(1).getCell(2)));
            Assertions.assertEquals("Blue Label " + seeded.numberAttributeKey().substring(seeded.numberAttributeKey().indexOf('-') + 1), formatter.formatCellValue(workbook.getSheet("枚举导出").getRow(2).getCell(4)));
        }
    }

    private List<String> rowValues(XSSFWorkbook workbook,
                                   String sheetName,
                                   int rowIndex,
                                   DataFormatter formatter) {
        return java.util.stream.IntStream.range(0, workbook.getSheet(sheetName).getRow(rowIndex).getLastCellNum())
                .mapToObj(cellIndex -> formatter.formatCellValue(workbook.getSheet(sheetName).getRow(rowIndex).getCell(cellIndex)))
                .toList();
    }

    private boolean hasLogCode(List<WorkbookExportLogEventDto> items,
                               String code) {
        return items.stream().map(WorkbookExportLogEventDto::getCode).anyMatch(code::equals);
    }

    private <T> T readValue(MvcResult result,
                            Class<T> targetType) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), targetType);
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private record SeededMetadata(UUID rootId,
                                  String rootCode,
                                  String childCode,
                                  String numberAttributeKey,
                                  String enumAttributeKey,
                                  String lovKey) {
    }

    @TestConfiguration
    static class SyncAsyncExecutorConfig {

        @Bean(name = "workbookImportTaskExecutor")
        SyncTaskExecutor workbookImportTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }
}