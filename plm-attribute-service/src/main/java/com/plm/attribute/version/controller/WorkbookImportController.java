package com.plm.attribute.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.attribute.version.service.workbook.WorkbookImportDryRunService;
import com.plm.attribute.version.service.workbook.WorkbookImportExecutionService;
import com.plm.attribute.version.service.workbook.WorkbookImportRuntimeService;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunOptionsDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportLogPageDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartRequestDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartResponseDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/meta/imports/workbook")
public class WorkbookImportController {

    private final WorkbookImportDryRunService dryRunService;
    private final WorkbookImportExecutionService executionService;
    private final WorkbookImportRuntimeService runtimeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkbookImportController(WorkbookImportDryRunService dryRunService,
                                    WorkbookImportExecutionService executionService,
                                    WorkbookImportRuntimeService runtimeService) {
        this.dryRunService = dryRunService;
        this.executionService = executionService;
        this.runtimeService = runtimeService;
    }

    @PostMapping(value = "/dry-run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkbookImportDryRunResponseDto dryRun(@RequestPart("file") MultipartFile file,
                                                  @RequestPart("options") String optionsJson,
                                                  @RequestParam(value = "operator", required = false) String operator) throws Exception {
        WorkbookImportDryRunOptionsDto options = objectMapper.readValue(optionsJson, WorkbookImportDryRunOptionsDto.class);
        return dryRunService.dryRun(file, operator, options);
    }

    @GetMapping("/sessions/{importSessionId}")
    public WorkbookImportDryRunResponseDto getSession(@PathVariable("importSessionId") String importSessionId) {
        return runtimeService.getSessionResponse(importSessionId);
    }

    @PostMapping("/import")
    public WorkbookImportStartResponseDto startImport(@RequestBody WorkbookImportStartRequestDto request) {
        return executionService.startImport(request);
    }

    @GetMapping("/jobs/{jobId}")
    public WorkbookImportJobStatusDto getJobStatus(@PathVariable("jobId") String jobId) {
        return runtimeService.getJobStatus(jobId);
    }

    @GetMapping("/jobs/{jobId}/logs")
    public WorkbookImportLogPageDto getLogs(@PathVariable("jobId") String jobId,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "limit", required = false) Integer limit,
                                            @RequestParam(value = "level", required = false) String level,
                                            @RequestParam(value = "stage", required = false) String stage,
                                            @RequestParam(value = "sheetName", required = false) String sheetName,
                                            @RequestParam(value = "rowNumber", required = false) Integer rowNumber) {
        return runtimeService.getLogs(jobId, cursor, limit, level, stage, sheetName, rowNumber);
    }

    @GetMapping(path = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("jobId") String jobId) {
        return runtimeService.subscribe(jobId);
    }
}