package com.plm.attribute.version.service.workbook;

import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunOptionsDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunStartResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.Executor;

@Service
public class WorkbookImportDryRunJobService {

    private final WorkbookImportRuntimeService runtimeService;
    private final WorkbookImportDryRunService dryRunService;
    private final Executor taskExecutor;

    public WorkbookImportDryRunJobService(WorkbookImportRuntimeService runtimeService,
                                          WorkbookImportDryRunService dryRunService,
                                          @Qualifier("workbookImportTaskExecutor") Executor taskExecutor) {
        this.runtimeService = runtimeService;
        this.dryRunService = dryRunService;
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    }

    public WorkbookImportDryRunStartResponseDto startDryRun(MultipartFile file,
                                                            String operator,
                                                            WorkbookImportDryRunOptionsDto options) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }

        byte[] fileContent;
        try {
            fileContent = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to read workbook", ex);
        }

        WorkbookImportSupport.JobState job = runtimeService.createDryRunJob(normalizeOperator(operator));
        runtimeService.appendLog(job.getJobId(), log -> {
            log.setLevel("INFO");
            log.setStage(WorkbookImportSupport.STAGE_PARSING);
            log.setEventType("TASK_ACCEPTED");
            log.setCode("WORKBOOK_DRY_RUN_ACCEPTED");
            log.setMessage("Workbook dry-run job accepted");
        });
        taskExecutor.execute(() -> executeJob(job.getJobId(), fileContent, file.getOriginalFilename(), operator, options));

        WorkbookImportDryRunStartResponseDto response = new WorkbookImportDryRunStartResponseDto();
        response.setJobId(job.getJobId());
        response.setStatus(WorkbookImportSupport.STATUS_QUEUED);
        response.setCurrentStage(WorkbookImportSupport.STAGE_PARSING);
        response.setCreatedAt(OffsetDateTime.now());
        return response;
    }

    void executeJob(String jobId,
                    byte[] fileContent,
                    String originalFilename,
                    String operator,
                    WorkbookImportDryRunOptionsDto options) {
        try {
            runtimeService.setStage(jobId, WorkbookImportSupport.STATUS_PARSING, WorkbookImportSupport.STAGE_PARSING);
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_PARSING);
                log.setEventType("TASK_STARTED");
                log.setCode("WORKBOOK_DRY_RUN_STARTED");
                log.setMessage("Workbook dry-run started");
            });

            WorkbookImportDryRunResponseDto result = dryRunService.dryRun(
                    fileContent,
                    originalFilename,
                    operator,
                    options,
                    new RuntimeDryRunProgressListener(jobId));

            runtimeService.saveDryRunResult(jobId, result);
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_FINALIZING);
                log.setEventType("TASK_COMPLETED");
                log.setCode("WORKBOOK_DRY_RUN_COMPLETED");
                log.setMessage("Workbook dry-run completed");
            });
            runtimeService.completeJob(jobId);
        } catch (Exception ex) {
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("ERROR");
                log.setStage(runtimeService.getJobStatus(jobId).getCurrentStage());
                log.setEventType("TASK_FAILED");
                log.setCode("WORKBOOK_DRY_RUN_FAILED");
                log.setMessage(ex.getMessage());
            });
            runtimeService.failJob(jobId, ex.getMessage());
        }
    }

    private String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "system";
        }
        return operator.trim();
    }

    private final class RuntimeDryRunProgressListener implements WorkbookImportDryRunService.DryRunProgressListener {

        private final String jobId;

        private RuntimeDryRunProgressListener(String jobId) {
            this.jobId = jobId;
        }

        @Override
        public void onPreloadingStarted() {
            runtimeService.setStage(jobId, WorkbookImportSupport.STATUS_PRELOADING, WorkbookImportSupport.STAGE_PRELOADING);
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_PRELOADING);
                log.setEventType("STAGE_STARTED");
                log.setCode("WORKBOOK_DRY_RUN_PRELOADING_STARTED");
                log.setMessage("Workbook dry-run preloading existing data");
            });
        }

        @Override
        public void onRowsParsed(int categoryCount, int attributeCount, int enumCount, String originalFilename) {
            runtimeService.mutateStatus(jobId, status -> {
                status.setStagePercent(100);
                WorkbookImportJobStatusDto.ProgressDto progress = status.getProgress();
                if (progress != null) {
                    progress.getCategories().setTotal(categoryCount);
                    progress.getCategories().setProcessed(0);
                    progress.getAttributes().setTotal(attributeCount);
                    progress.getAttributes().setProcessed(0);
                    progress.getEnumOptions().setTotal(enumCount);
                    progress.getEnumOptions().setProcessed(0);
                }
            });
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_PARSING);
                log.setEventType("STAGE_COMPLETED");
                log.setCode("WORKBOOK_DRY_RUN_PARSED");
                log.setMessage("Workbook parsed: categories=" + categoryCount
                        + ", attributes=" + attributeCount
                        + ", enumOptions=" + enumCount
                        + (originalFilename == null || originalFilename.isBlank() ? "" : ", file=" + originalFilename));
            });
            runtimeService.setStage(jobId, WorkbookImportSupport.STATUS_VALIDATING_CATEGORIES, WorkbookImportSupport.STAGE_VALIDATING_CATEGORIES);
        }

        @Override
        public void onCategoriesResolved(int count) {
            runtimeService.mutateStatus(jobId, status -> {
                status.setStagePercent(100);
                WorkbookImportJobStatusDto.ProgressDto progress = status.getProgress();
                if (progress != null && progress.getCategories() != null) {
                    progress.getCategories().setProcessed(count);
                }
            });
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_VALIDATING_CATEGORIES);
                log.setEventType("STAGE_COMPLETED");
                log.setCode("WORKBOOK_DRY_RUN_CATEGORY_ANALYZED");
                log.setMessage("Category dry-run analysis completed");
            });
            runtimeService.setStage(jobId, WorkbookImportSupport.STATUS_VALIDATING_ATTRIBUTES, WorkbookImportSupport.STAGE_VALIDATING_ATTRIBUTES);
        }

        @Override
        public void onAttributesResolved(int count) {
            runtimeService.mutateStatus(jobId, status -> {
                status.setStagePercent(100);
                WorkbookImportJobStatusDto.ProgressDto progress = status.getProgress();
                if (progress != null && progress.getAttributes() != null) {
                    progress.getAttributes().setProcessed(count);
                }
            });
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_VALIDATING_ATTRIBUTES);
                log.setEventType("STAGE_COMPLETED");
                log.setCode("WORKBOOK_DRY_RUN_ATTRIBUTE_ANALYZED");
                log.setMessage("Attribute dry-run analysis completed");
            });
            runtimeService.setStage(jobId, WorkbookImportSupport.STATUS_VALIDATING_ENUMS, WorkbookImportSupport.STAGE_VALIDATING_ENUMS);
        }

        @Override
        public void onEnumOptionsResolved(int count) {
            runtimeService.mutateStatus(jobId, status -> {
                status.setStagePercent(100);
                WorkbookImportJobStatusDto.ProgressDto progress = status.getProgress();
                if (progress != null && progress.getEnumOptions() != null) {
                    progress.getEnumOptions().setProcessed(count);
                }
            });
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_VALIDATING_ENUMS);
                log.setEventType("STAGE_COMPLETED");
                log.setCode("WORKBOOK_DRY_RUN_ENUM_ANALYZED");
                log.setMessage("Enum option dry-run analysis completed");
            });
        }

        @Override
        public void onPreviewBuilding() {
            runtimeService.setStage(jobId, WorkbookImportSupport.STATUS_BUILDING_PREVIEW, WorkbookImportSupport.STAGE_BUILDING_PREVIEW);
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_BUILDING_PREVIEW);
                log.setEventType("STAGE_STARTED");
                log.setCode("WORKBOOK_DRY_RUN_PREVIEW_BUILDING");
                log.setMessage("Workbook dry-run is building preview and execution plan snapshot");
            });
        }

        @Override
        public void onCompleted(WorkbookImportDryRunResponseDto response) {
            runtimeService.saveDryRunResult(jobId, response);
            runtimeService.mutateStatus(jobId, status -> {
                status.setImportSessionId(response.getImportSessionId());
                status.setStagePercent(100);
                applyChangeSummary(status.getProgress(), response.getChangeSummary());
            });
        }

        private void applyChangeSummary(WorkbookImportJobStatusDto.ProgressDto progress,
                                        WorkbookImportDryRunResponseDto.ChangeSummaryDto changeSummary) {
            if (progress == null || changeSummary == null) {
                return;
            }
            applyChangeCounter(progress.getCategories(), changeSummary.getCategories());
            applyChangeCounter(progress.getAttributes(), changeSummary.getAttributes());
            applyChangeCounter(progress.getEnumOptions(), changeSummary.getEnumOptions());
        }

        private void applyChangeCounter(WorkbookImportJobStatusDto.EntityProgressDto progress,
                                        WorkbookImportDryRunResponseDto.ChangeCounterDto counter) {
            if (progress == null || counter == null) {
                return;
            }
            progress.setCreated(defaultZero(counter.getCreate()));
            progress.setUpdated(defaultZero(counter.getUpdate()));
            progress.setSkipped(defaultZero(counter.getSkip()));
            progress.setFailed(defaultZero(counter.getConflict()));
        }

        private int defaultZero(Integer value) {
            return value == null ? 0 : value;
        }
    }
}