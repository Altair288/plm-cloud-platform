package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.workbook.export.WorkbookExportJobService;
import com.plm.attribute.version.service.workbook.export.WorkbookExportRuntimeService;
import com.plm.attribute.version.service.workbook.export.WorkbookExportSchemaService;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobResultDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobStatusDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportLogPageDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportPlanResponseDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportSchemaResponseDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartRequestDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartResponseDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/meta/exports/workbook")
public class WorkbookExportController {

    private final WorkbookExportSchemaService schemaService;
    private final WorkbookExportJobService jobService;
    private final WorkbookExportRuntimeService runtimeService;

    public WorkbookExportController(WorkbookExportSchemaService schemaService,
                                    WorkbookExportJobService jobService,
                                    WorkbookExportRuntimeService runtimeService) {
        this.schemaService = schemaService;
        this.jobService = jobService;
        this.runtimeService = runtimeService;
    }

    @GetMapping("/schema")
    public WorkbookExportSchemaResponseDto schema() {
        return schemaService.schema();
    }

    @PostMapping("/plan")
    public WorkbookExportPlanResponseDto plan(@RequestBody WorkbookExportStartRequestDto request) {
        return jobService.plan(request);
    }

    @PostMapping("/jobs")
    public WorkbookExportStartResponseDto startJob(@RequestBody WorkbookExportStartRequestDto request) {
        return jobService.startJob(request);
    }

    @GetMapping("/jobs/{jobId}")
    public WorkbookExportJobStatusDto getJobStatus(@PathVariable("jobId") String jobId) {
        return runtimeService.getJobStatus(jobId);
    }

    @GetMapping("/jobs/{jobId}/logs")
    public WorkbookExportLogPageDto getLogs(@PathVariable("jobId") String jobId,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "limit", required = false) Integer limit,
                                            @RequestParam(value = "level", required = false) String level,
                                            @RequestParam(value = "stage", required = false) String stage,
                                            @RequestParam(value = "moduleKey", required = false) String moduleKey) {
        return runtimeService.getLogs(jobId, cursor, limit, level, stage, moduleKey);
    }

    @GetMapping(path = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("jobId") String jobId) {
        return runtimeService.subscribe(jobId);
    }

    @GetMapping("/jobs/{jobId}/result")
    public WorkbookExportJobResultDto getResult(@PathVariable("jobId") String jobId) {
        return runtimeService.getResult(jobId);
    }

    @DeleteMapping("/jobs/{jobId}")
    public WorkbookExportJobStatusDto cancel(@PathVariable("jobId") String jobId) {
        return runtimeService.cancelJob(jobId);
    }

    @GetMapping("/jobs/{jobId}/download")
    public ResponseEntity<byte[]> download(@PathVariable("jobId") String jobId) {
        WorkbookExportJobResultDto result = runtimeService.getResult(jobId);
        byte[] content = runtimeService.getFileContent(jobId);
        WorkbookExportJobResultDto.FileDto file = result.getFile();
        MediaType mediaType = MediaType.parseMediaType(file.getContentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(mediaType)
                .contentLength(content.length)
                .body(content);
    }
}