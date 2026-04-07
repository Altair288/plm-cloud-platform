package com.plm.attribute.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunOptionsDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunStartResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportLogEventDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportLogPageDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartRequestDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartResponseDto;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
@Import(WorkbookImportControllerIT.SyncAsyncExecutorConfig.class)
class WorkbookImportControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dryRunAndSessionEndpoints_shouldReturnPreviewPayload() throws Exception {
        String suffix = uniqueSuffix();
        MockMultipartFile workbook = Objects.requireNonNull(createAutoWorkbook(suffix));
        MockMultipartFile options = Objects.requireNonNull(jsonPart("options", autoOptions()));

        MvcResult dryRunResult = mockMvc.perform(multipart("/api/meta/imports/workbook/dry-run")
                        .file(workbook)
                        .file(options)
                        .param("operator", "it-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importSessionId").isNotEmpty())
                .andExpect(jsonPath("$.summary.errorCount").value(0))
                .andExpect(jsonPath("$.summary.canImport").value(true))
                .andExpect(jsonPath("$.changeSummary.categories.create").value(2))
                .andExpect(jsonPath("$.changeSummary.attributes.create").value(1))
                .andExpect(jsonPath("$.changeSummary.enumOptions.create").value(2))
                .andReturn();

        WorkbookImportDryRunResponseDto dryRun = readValue(dryRunResult, WorkbookImportDryRunResponseDto.class);

        mockMvc.perform(get("/api/meta/imports/workbook/sessions/{importSessionId}", dryRun.getImportSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importSessionId").value(dryRun.getImportSessionId()))
            .andExpect(jsonPath("$.preview.categories[0].rowNumber").value(4))
            .andExpect(jsonPath("$.preview.attributes[0].rowNumber").value(3))
            .andExpect(jsonPath("$.preview.enumOptions[0].rowNumber").value(3))
                .andExpect(jsonPath("$.preview.categories.length()").value(2))
                .andExpect(jsonPath("$.preview.attributes.length()").value(1))
                .andExpect(jsonPath("$.preview.enumOptions.length()").value(2));
    }

    @Test
    void dryRun_shouldReturnBadRequestWhenOptionsJsonIsInvalid() throws Exception {
        String suffix = uniqueSuffix();
        MockMultipartFile workbook = Objects.requireNonNull(createAutoWorkbook(suffix));
        MockMultipartFile invalidOptions = new MockMultipartFile(
                "options",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                "{invalid-json".getBytes());

        mockMvc.perform(multipart("/api/meta/imports/workbook/dry-run")
                        .file(workbook)
                        .file(invalidOptions)
                        .param("operator", "it-user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("invalid workbook import options format")));
    }

    @Test
    void streamEndpoint_shouldReturnEventStreamErrorPayloadWhenJobDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/meta/imports/workbook/jobs/{jobId}/stream", "missing-job")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"INVALID_ARGUMENT\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("workbook import job not found")));
    }

            @Test
            void dryRunJobEndpoints_shouldExposeAsyncWorkflow() throws Exception {
            String suffix = uniqueSuffix();
            MockMultipartFile workbook = Objects.requireNonNull(createAutoWorkbook(suffix));
            MockMultipartFile options = Objects.requireNonNull(jsonPart("options", autoOptions()));

            MvcResult startResult = mockMvc.perform(multipart("/api/meta/imports/workbook/dry-run-jobs")
                    .file(workbook)
                    .file(options)
                    .param("operator", "it-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andReturn();
            WorkbookImportDryRunStartResponseDto start = readValue(startResult, WorkbookImportDryRunStartResponseDto.class);

            mockMvc.perform(get("/api/meta/imports/workbook/dry-run-jobs/{jobId}", start.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobType").value("DRY_RUN"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINALIZING"))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.processedRows").value(5))
                .andExpect(jsonPath("$.progress.categories.total").value(2))
                .andExpect(jsonPath("$.progress.categories.processed").value(2))
                .andExpect(jsonPath("$.progress.attributes.total").value(1))
                .andExpect(jsonPath("$.progress.attributes.processed").value(1))
                .andExpect(jsonPath("$.progress.enumOptions.total").value(2))
                .andExpect(jsonPath("$.progress.enumOptions.processed").value(2))
                .andExpect(jsonPath("$.importSessionId").isNotEmpty());

            MvcResult result = mockMvc.perform(get("/api/meta/imports/workbook/dry-run-jobs/{jobId}/result", start.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importSessionId").isNotEmpty())
                .andExpect(jsonPath("$.summary.errorCount").value(0))
                .andExpect(jsonPath("$.summary.canImport").value(true))
                .andReturn();
            WorkbookImportDryRunResponseDto dryRun = readValue(result, WorkbookImportDryRunResponseDto.class);

            mockMvc.perform(get("/api/meta/imports/workbook/dry-run-jobs/{jobId}/result", start.getJobId())
                    .param("entityType", "CATEGORY")
                    .param("page", "0")
                    .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importSessionId").value(dryRun.getImportSessionId()))
                .andExpect(jsonPath("$.previewEntityType").value("CATEGORY"))
                .andExpect(jsonPath("$.previewPage.number").value(0))
                .andExpect(jsonPath("$.previewPage.size").value(1))
                .andExpect(jsonPath("$.previewPage.totalElements").value(2))
                .andExpect(jsonPath("$.preview.categories.length()").value(1))
                .andExpect(jsonPath("$.preview.attributes.length()").value(0))
                .andExpect(jsonPath("$.preview.enumOptions.length()").value(0))
                .andExpect(jsonPath("$.issues.length()").value(0));

            mockMvc.perform(get("/api/meta/imports/workbook/sessions/{importSessionId}", dryRun.getImportSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importSessionId").value(dryRun.getImportSessionId()));

            MvcResult logResult = mockMvc.perform(get("/api/meta/imports/workbook/dry-run-jobs/{jobId}/logs", start.getJobId())
                    .param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(start.getJobId()))
                .andExpect(jsonPath("$.items.length()").isNotEmpty())
                .andReturn();
            WorkbookImportLogPageDto logPage = readValue(logResult, WorkbookImportLogPageDto.class);
            Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_DRY_RUN_PRELOADING_STARTED"));
            Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_DRY_RUN_PARSED"));
            Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_DRY_RUN_CATEGORY_ANALYZED"));
            Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_DRY_RUN_ATTRIBUTE_ANALYZED"));
            Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_DRY_RUN_ENUM_ANALYZED"));
            Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_DRY_RUN_PREVIEW_BUILDING"));
            Assertions.assertTrue(hasLogCode(logPage.getItems(), "WORKBOOK_DRY_RUN_COMPLETED"));

            MvcResult streamResult = mockMvc.perform(get("/api/meta/imports/workbook/dry-run-jobs/{jobId}/stream", start.getJobId()))
                .andExpect(request().asyncStarted())
                .andReturn();
            Objects.requireNonNull(streamResult.getRequest().getAsyncContext(), "asyncContext").complete();
        }

    @Test
    void importJobLogsAndStreamEndpoints_shouldExposeHttpWorkflow() throws Exception {
        String suffix = uniqueSuffix();
        WorkbookImportDryRunResponseDto dryRun = performDryRun(suffix);

        WorkbookImportStartRequestDto startRequest = new WorkbookImportStartRequestDto();
        startRequest.setImportSessionId(dryRun.getImportSessionId());
        startRequest.setOperator("it-user");
        startRequest.setAtomic(Boolean.FALSE);
        startRequest.setExecutionMode("STAGE_TX");

        MvcResult startResult = mockMvc.perform(post("/api/meta/imports/workbook/import-jobs")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(Objects.requireNonNull(objectMapper.writeValueAsBytes(startRequest))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.importSessionId").value(dryRun.getImportSessionId()))
            .andExpect(jsonPath("$.atomic").value(false))
            .andExpect(jsonPath("$.executionMode").value("STAGE_TX"))
                .andReturn();

        WorkbookImportStartResponseDto start = readValue(startResult, WorkbookImportStartResponseDto.class);

        MvcResult statusResult = mockMvc.perform(get("/api/meta/imports/workbook/jobs/{jobId}", start.getJobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINALIZING"))
                .andExpect(jsonPath("$.executionMode").value("STAGE_TX"))
            .andExpect(jsonPath("$.totalRows").value(5))
            .andExpect(jsonPath("$.processedRows").value(5))
                .andExpect(jsonPath("$.progress.categories.created").value(2))
                .andExpect(jsonPath("$.progress.attributes.created").value(1))
                .andExpect(jsonPath("$.progress.enumOptions.created").value(2))
                .andReturn();
        WorkbookImportJobStatusDto jobStatus = readValue(statusResult, WorkbookImportJobStatusDto.class);
        Assertions.assertEquals(100, jobStatus.getOverallPercent());

        MvcResult logResult = mockMvc.perform(get("/api/meta/imports/workbook/jobs/{jobId}/logs", start.getJobId())
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(start.getJobId()))
                .andExpect(jsonPath("$.items.length()").isNotEmpty())
                .andReturn();
        WorkbookImportLogPageDto logPage = readValue(logResult, WorkbookImportLogPageDto.class);

        Assertions.assertTrue(hasLogCode(logPage.getItems(), "CATEGORY_IMPORTED"));
        Assertions.assertTrue(hasLogCode(logPage.getItems(), "ATTRIBUTE_IMPORTED"));
        Assertions.assertTrue(hasLogCode(logPage.getItems(), "ENUM_OPTION_IMPORTED"));
        Assertions.assertFalse(hasLogCode(logPage.getItems(), "CATEGORY_CODE_SEQUENCE_RESERVED"));

        mockMvc.perform(post("/api/meta/imports/workbook/jobs/{jobId}/post-process/closure-rebuild", start.getJobId())
                .param("operator", "repair-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(start.getJobId()))
            .andExpect(jsonPath("$.action").value("REBUILD_CLOSURE"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.executionMode").value("STAGE_TX"))
            .andExpect(jsonPath("$.details.strategy").value("ancestor_list_dp"));

        MvcResult streamResult = mockMvc.perform(get("/api/meta/imports/workbook/jobs/{jobId}/stream", start.getJobId()))
                .andExpect(request().asyncStarted())
                .andReturn();
        Objects.requireNonNull(streamResult.getRequest().getAsyncContext(), "asyncContext").complete();
    }

        @Test
        void importEndpoint_shouldAcceptDryRunJobId() throws Exception {
        String suffix = uniqueSuffix();
        MockMultipartFile workbook = Objects.requireNonNull(createAutoWorkbook(suffix));
        MockMultipartFile options = Objects.requireNonNull(jsonPart("options", autoOptions()));

        MvcResult dryRunStartResult = mockMvc.perform(multipart("/api/meta/imports/workbook/dry-run-jobs")
                .file(workbook)
                .file(options)
                .param("operator", "it-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn();
        WorkbookImportDryRunStartResponseDto dryRunStart = readValue(dryRunStartResult, WorkbookImportDryRunStartResponseDto.class);

        WorkbookImportStartRequestDto importRequest = new WorkbookImportStartRequestDto();
        importRequest.setDryRunJobId(dryRunStart.getJobId());
        importRequest.setOperator("it-user");
        importRequest.setAtomic(Boolean.TRUE);

        mockMvc.perform(post("/api/meta/imports/workbook/import-jobs")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .content(Objects.requireNonNull(objectMapper.writeValueAsBytes(importRequest))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andExpect(jsonPath("$.importSessionId").isNotEmpty());
        }

    @Test
    void importEndpoint_shouldSupportStagingAtomicExecutionMode() throws Exception {
        String suffix = uniqueSuffix();
        WorkbookImportDryRunResponseDto dryRun = performDryRun(suffix);

        WorkbookImportStartRequestDto importRequest = new WorkbookImportStartRequestDto();
        importRequest.setImportSessionId(dryRun.getImportSessionId());
        importRequest.setOperator("it-user");
        importRequest.setAtomic(Boolean.TRUE);
        importRequest.setExecutionMode("STAGING_ATOMIC");

        MvcResult startResult = mockMvc.perform(post("/api/meta/imports/workbook/import-jobs")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .content(Objects.requireNonNull(objectMapper.writeValueAsBytes(importRequest))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andExpect(jsonPath("$.atomic").value(true))
            .andExpect(jsonPath("$.executionMode").value("STAGING_ATOMIC"))
            .andReturn();

        WorkbookImportStartResponseDto start = readValue(startResult, WorkbookImportStartResponseDto.class);

        mockMvc.perform(get("/api/meta/imports/workbook/jobs/{jobId}", start.getJobId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.executionMode").value("STAGING_ATOMIC"))
            .andExpect(jsonPath("$.progress.categories.created").value(2))
            .andExpect(jsonPath("$.progress.attributes.created").value(1))
            .andExpect(jsonPath("$.progress.enumOptions.created").value(2));

        mockMvc.perform(get("/api/meta/imports/workbook/jobs/{jobId}/logs", start.getJobId())
                .param("limit", "200"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.code=='WORKBOOK_STAGING_PLAN_PREPARED')]").isNotEmpty())
            .andExpect(jsonPath("$.items[?(@.code=='WORKBOOK_STAGING_MERGE_STARTED')]").isNotEmpty());
    }

    private WorkbookImportDryRunResponseDto performDryRun(String suffix) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/meta/imports/workbook/dry-run")
                        .file(Objects.requireNonNull(createAutoWorkbook(suffix)))
                        .file(Objects.requireNonNull(jsonPart("options", autoOptions())))
                        .param("operator", "it-user"))
                .andExpect(status().isOk())
                .andReturn();
        return readValue(result, WorkbookImportDryRunResponseDto.class);
    }

    private MockMultipartFile jsonPart(String name, Object value) throws Exception {
        return new MockMultipartFile(Objects.requireNonNull(name), "", MediaType.APPLICATION_JSON_VALUE, Objects.requireNonNull(objectMapper.writeValueAsBytes(value)));
    }

    private WorkbookImportDryRunOptionsDto autoOptions() {
        WorkbookImportDryRunOptionsDto options = new WorkbookImportDryRunOptionsDto();
        options.getCodingOptions().setCategoryCodeMode("SYSTEM_RULE_AUTO");
        options.getCodingOptions().setAttributeCodeMode("SYSTEM_RULE_AUTO");
        options.getCodingOptions().setEnumOptionCodeMode("SYSTEM_RULE_AUTO");
        options.getDuplicateOptions().setCategoryDuplicatePolicy("FAIL_ON_DUPLICATE");
        options.getDuplicateOptions().setAttributeDuplicatePolicy("FAIL_ON_DUPLICATE");
        options.getDuplicateOptions().setEnumOptionDuplicatePolicy("FAIL_ON_DUPLICATE");
        return options;
    }

    private boolean hasLogCode(List<WorkbookImportLogEventDto> items, String code) {
        return items.stream().map(WorkbookImportLogEventDto::getCode).anyMatch(code::equals);
    }

    private <T> T readValue(MvcResult result, Class<T> targetType) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), targetType);
    }

    private MockMultipartFile createAutoWorkbook(String suffix) throws Exception {
        String rootCode = "ROOT-" + suffix;
        String childCode = "CHILD-" + suffix;
        String attributeCode = "ATTR-REF-" + suffix;
        return createWorkbook(
                "workbook-auto-" + suffix + ".xlsx",
                List.<String[]>of(
                        new String[]{"MATERIAL", rootCode, "/" + rootCode, "Root " + suffix},
                        new String[]{"MATERIAL", childCode, "/" + rootCode + "/" + childCode, "Child " + suffix}
                ),
                List.<String[]>of(
                        attributeRow(childCode, "Child " + suffix, attributeCode, "Color " + suffix, "color" + suffix.toLowerCase(), "enum")
                ),
                List.<String[]>of(
                        new String[]{childCode, attributeCode, "ENUM-REF-A-" + suffix, "Red " + suffix, "Red " + suffix},
                        new String[]{childCode, attributeCode, "ENUM-REF-B-" + suffix, "Blue " + suffix, "Blue " + suffix}
                )
        );
    }

    private String[] attributeRow(String categoryCode,
                                  String categoryName,
                                  String attributeKey,
                                  String attributeName,
                                  String attributeField,
                                  String dataType) {
        return new String[]{
                categoryCode,
                categoryName,
                attributeKey,
                attributeName,
                attributeField,
                "",
                dataType,
                "",
                "",
                "N",
                "N",
                "Y",
                "N",
                "N",
                "",
                "",
                "",
                "",
                "",
                ""
        };
    }

    private MockMultipartFile createWorkbook(String fileName,
                                             List<String[]> categoryRows,
                                             List<String[]> attributeRows,
                                             List<String[]> enumRows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet categories = workbook.createSheet("分类层级");
            writeRow(categories, 0, "分类层级：填写真实编码、完整路径、分类名称，右侧自动校验与预览");
            writeRow(categories, 1, "", "用户填写区", "", "");
            writeRow(categories, 2, "Business_Domain", "Category_Code", "Category_Path", "Category_Name");
            for (int index = 0; index < categoryRows.size(); index++) {
                writeRow(categories, index + 3, categoryRows.get(index));
            }

            Sheet attributes = workbook.createSheet("属性定义");
            writeRow(attributes, 0, "属性定义：填写属性基础信息，前两行为模板说明与表头");
            writeRow(attributes, 1,
                    "Category_Code",
                    "Category_Name",
                    "Attribute_Key",
                    "Attribute_Name",
                    "Attribute_Field",
                    "Description",
                    "Data_Type",
                    "Unit",
                    "Default_Value",
                    "Required",
                    "Unique",
                    "Searchable",
                    "Hidden",
                    "Read_Only",
                    "Min_Value",
                    "Max_Value",
                    "Step",
                    "Precision",
                    "True_Label",
                    "False_Label");
            for (int index = 0; index < attributeRows.size(); index++) {
                writeRow(attributes, index + 2, attributeRows.get(index));
            }

            Sheet enums = workbook.createSheet("枚举值定义");
            writeRow(enums, 0, "枚举值定义：填写枚举型属性的候选值，前两行为模板说明与表头");
            writeRow(enums, 1, "Category_Code", "Attribute_Key", "Option_Code", "Option_Name", "Display_Label");
            for (int index = 0; index < enumRows.size(); index++) {
                writeRow(enums, index + 2, enumRows.get(index));
            }

            workbook.write(output);
            return new MockMultipartFile(
                    "file",
                    fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray());
        }
    }

    private void writeRow(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int index = 0; index < values.length; index++) {
            row.createCell(index).setCellValue(values[index]);
        }
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    @TestConfiguration
    static class SyncAsyncExecutorConfig {

        @Bean(name = "workbookImportTaskExecutor")
        SyncTaskExecutor workbookImportTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }
}