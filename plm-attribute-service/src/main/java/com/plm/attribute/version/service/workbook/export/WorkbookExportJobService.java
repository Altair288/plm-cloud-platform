package com.plm.attribute.version.service.workbook.export;

import com.plm.common.api.dto.exports.workbook.WorkbookExportJobResultDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportPlanResponseDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartRequestDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

@Service
public class WorkbookExportJobService {

    private final Executor taskExecutor;
    private final WorkbookExportSchemaService schemaService;
    private final WorkbookExportDataService dataService;
    private final WorkbookExportWorkbookBuilder workbookBuilder;
    private final WorkbookExportRuntimeService runtimeService;

    public WorkbookExportJobService(@Qualifier("workbookImportTaskExecutor") Executor taskExecutor,
                                    WorkbookExportSchemaService schemaService,
                                    WorkbookExportDataService dataService,
                                    WorkbookExportWorkbookBuilder workbookBuilder,
                                    WorkbookExportRuntimeService runtimeService) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
        this.dataService = Objects.requireNonNull(dataService, "dataService");
        this.workbookBuilder = Objects.requireNonNull(workbookBuilder, "workbookBuilder");
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService");
    }

    public WorkbookExportStartResponseDto startJob(WorkbookExportStartRequestDto request) {
        WorkbookExportStartRequestDto normalized = schemaService.normalizeRequest(request);
        String fileName = normalized.getOutput().getFileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = normalized.getBusinessDomain().trim().toLowerCase() + "-workbook-export.xlsx";
        }
        WorkbookExportSupport.JobState job = runtimeService.createJob(normalized, fileName);
        runtimeService.appendLog(job.getJobId(), event -> {
            event.setLevel("INFO");
            event.setStage(WorkbookExportSupport.STAGE_RESOLVING_SCOPE);
            event.setEventType("JOB_QUEUED");
            event.setCode("WORKBOOK_EXPORT_QUEUED");
            event.setMessage("workbook export job queued");
        });
        taskExecutor.execute(() -> execute(job.getJobId(), normalized));

        WorkbookExportStartResponseDto response = new WorkbookExportStartResponseDto();
        response.setJobId(job.getJobId());
        response.setStatus(WorkbookExportSupport.STATUS_QUEUED);
        response.setCurrentStage(WorkbookExportSupport.STAGE_RESOLVING_SCOPE);
        response.setCreatedAt(runtimeService.getJobStatus(job.getJobId()).getCreatedAt());
        return response;
    }

    public WorkbookExportPlanResponseDto plan(WorkbookExportStartRequestDto request) {
        WorkbookExportStartRequestDto normalized = schemaService.normalizeRequest(request);
        List<UUID> scopeCategoryIds = dataService.resolveScopeCategoryIds(normalized);
        WorkbookExportDataService.ExportEstimate estimateResult = dataService.estimateRows(normalized.getBusinessDomain(), scopeCategoryIds);

        WorkbookExportPlanResponseDto response = new WorkbookExportPlanResponseDto();
        response.setNormalizedRequest(normalized);
        response.setWarnings(List.of());

        WorkbookExportPlanResponseDto.EstimateDto estimate = new WorkbookExportPlanResponseDto.EstimateDto();
        estimate.setCategoryRows(estimateResult.categoryRows());
        estimate.setAttributeRows(estimateResult.attributeRows());
        estimate.setEnumOptionRows(estimateResult.enumOptionRows());
        response.setEstimate(estimate);
        return response;
    }

    private void execute(String jobId,
                         WorkbookExportStartRequestDto request) {
        try {
            runtimeService.updateStage(jobId, WorkbookExportSupport.STATUS_RESOLVING_SCOPE, WorkbookExportSupport.STAGE_RESOLVING_SCOPE, 5, 0);
            runtimeService.ensureNotCanceled(jobId);
            runtimeService.appendLog(jobId, event -> {
                event.setLevel("INFO");
                event.setStage(WorkbookExportSupport.STAGE_RESOLVING_SCOPE);
                event.setEventType("RESOLVE_SCOPE_STARTED");
                event.setCode("WORKBOOK_EXPORT_SCOPE_START");
                event.setMessage("resolving export category scope");
            });
            List<UUID> scopeCategoryIds = dataService.resolveScopeCategoryIds(request);
            runtimeService.appendLog(jobId, event -> {
                event.setLevel("INFO");
                event.setStage(WorkbookExportSupport.STAGE_RESOLVING_SCOPE);
                event.setEventType("RESOLVE_SCOPE_COMPLETED");
                event.setCode("WORKBOOK_EXPORT_SCOPE_DONE");
                event.setMessage("resolved export category scope");
                event.setDetails(Map.of("categoryCount", scopeCategoryIds.size()));
            });

            runtimeService.ensureNotCanceled(jobId);
            runtimeService.updateStage(jobId, WorkbookExportSupport.STATUS_LOADING_CATEGORIES, WorkbookExportSupport.STAGE_LOADING_CATEGORIES, 20, 0);
            WorkbookExportSupport.ExportDataBundle dataBundle = dataService.loadData(request.getBusinessDomain(), scopeCategoryIds);
            runtimeService.setModuleProgress(jobId, WorkbookExportSupport.MODULE_CATEGORY, dataBundle.categories().size(), dataBundle.categories().size(), dataBundle.categories().size(), 0);
            runtimeService.updateStage(jobId, WorkbookExportSupport.STATUS_LOADING_ATTRIBUTES, WorkbookExportSupport.STAGE_LOADING_ATTRIBUTES, 45, 100);
            runtimeService.setModuleProgress(jobId, WorkbookExportSupport.MODULE_ATTRIBUTE, dataBundle.attributes().size(), dataBundle.attributes().size(), dataBundle.attributes().size(), 0);
            runtimeService.updateStage(jobId, WorkbookExportSupport.STATUS_LOADING_ENUM_OPTIONS, WorkbookExportSupport.STAGE_LOADING_ENUM_OPTIONS, 65, 100);
            runtimeService.setModuleProgress(jobId, WorkbookExportSupport.MODULE_ENUM_OPTION, dataBundle.enumOptions().size(), dataBundle.enumOptions().size(), dataBundle.enumOptions().size(), 0);
            runtimeService.appendLog(jobId, event -> {
                event.setLevel("INFO");
                event.setStage(WorkbookExportSupport.STAGE_LOADING_ENUM_OPTIONS);
                event.setEventType("LOAD_DATA_COMPLETED");
                event.setCode("WORKBOOK_EXPORT_DATA_DONE");
                event.setMessage("loaded workbook export data");
                event.setDetails(Map.of(
                        "categoryRows", dataBundle.categories().size(),
                        "attributeRows", dataBundle.attributes().size(),
                        "enumRows", dataBundle.enumOptions().size()));
            });

            runtimeService.ensureNotCanceled(jobId);
            runtimeService.updateStage(jobId, WorkbookExportSupport.STATUS_BUILDING_WORKBOOK, WorkbookExportSupport.STAGE_BUILDING_WORKBOOK, 80, 0);
            runtimeService.appendLog(jobId, event -> {
                event.setLevel("INFO");
                event.setStage(WorkbookExportSupport.STAGE_BUILDING_WORKBOOK);
                event.setEventType("WORKBOOK_BUILD_STARTED");
                event.setCode("WORKBOOK_EXPORT_BUILD_START");
                event.setMessage("building xlsx workbook export file");
            });
            WorkbookExportSupport.ExportArtifact artifact = workbookBuilder.build(request, dataBundle, schemaService);

            runtimeService.ensureNotCanceled(jobId);
            runtimeService.updateStage(jobId, WorkbookExportSupport.STATUS_STORING_FILE, WorkbookExportSupport.STAGE_STORING_FILE, 95, 100);
            WorkbookExportJobResultDto result = artifact.result();
            runtimeService.complete(jobId, result, artifact.content());
            runtimeService.appendLog(jobId, event -> {
                event.setLevel("INFO");
                event.setStage(WorkbookExportSupport.STAGE_STORING_FILE);
                event.setEventType("JOB_COMPLETED");
                event.setCode("WORKBOOK_EXPORT_COMPLETED");
                event.setMessage("workbook export job completed");
                event.setDetails(Map.of(
                        "fileName", result.getFile().getFileName(),
                        "fileSize", result.getFile().getSize()));
            });
        } catch (CancellationException ex) {
            runtimeService.cancelJob(jobId);
        } catch (Exception ex) {
            runtimeService.fail(jobId, ex);
        }
    }
}