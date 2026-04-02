package com.plm.attribute.version.service.workbook;

import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunOptionsDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportLogEventDto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.UUID;

public final class WorkbookImportSupport {

    public static final String JOB_TYPE_IMPORT = "IMPORT";
    public static final String JOB_TYPE_DRY_RUN = "DRY_RUN";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_PRELOADING = "PRELOADING";
    public static final String STATUS_VALIDATING_CATEGORIES = "VALIDATING_CATEGORIES";
    public static final String STATUS_VALIDATING_ATTRIBUTES = "VALIDATING_ATTRIBUTES";
    public static final String STATUS_VALIDATING_ENUMS = "VALIDATING_ENUMS";
    public static final String STATUS_BUILDING_PREVIEW = "BUILDING_PREVIEW";
    public static final String STATUS_PREPARING = "PREPARING";
    public static final String STATUS_IMPORTING_CATEGORIES = "IMPORTING_CATEGORIES";
    public static final String STATUS_IMPORTING_ATTRIBUTES = "IMPORTING_ATTRIBUTES";
    public static final String STATUS_IMPORTING_ENUM_OPTIONS = "IMPORTING_ENUM_OPTIONS";
    public static final String STATUS_FINALIZING = "FINALIZING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STAGE_PARSING = "PARSING";
    public static final String STAGE_PRELOADING = "PRELOADING";
    public static final String STAGE_VALIDATING_CATEGORIES = "VALIDATING_CATEGORIES";
    public static final String STAGE_VALIDATING_ATTRIBUTES = "VALIDATING_ATTRIBUTES";
    public static final String STAGE_VALIDATING_ENUMS = "VALIDATING_ENUMS";
    public static final String STAGE_BUILDING_PREVIEW = "BUILDING_PREVIEW";
    public static final String STAGE_PREPARING = "PREPARING";
    public static final String STAGE_CATEGORIES = "CATEGORIES";
    public static final String STAGE_ATTRIBUTES = "ATTRIBUTES";
    public static final String STAGE_ENUM_OPTIONS = "ENUM_OPTIONS";
    public static final String STAGE_FINALIZING = "FINALIZING";

    private WorkbookImportSupport() {
    }

    public record ImportSessionState(
            String importSessionId,
            String operator,
            WorkbookImportDryRunOptionsDto options,
            WorkbookImportDryRunResponseDto response,
            List<ParsedCategoryRow> categories,
            List<ParsedAttributeRow> attributes,
            List<ParsedEnumOptionRow> enumOptions,
            ExistingDataSnapshot existingData,
            ExecutionPlanSnapshot executionPlan,
            ExecutionPlanSnapshot stagedExecutionPlan,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt
    ) {
    }

            public record ExistingDataSnapshot(
            Map<String, ExistingCategoryRef> categoriesByDomainCode,
            Map<String, ExistingCategoryRef> categoriesByDomainPath,
            Set<String> ambiguousCategoryCodes,
            Map<String, ExistingAttributeRef> attributesByDomainKey,
            Map<String, Map<String, ExistingEnumValueRef>> enumValuesByBusinessDomain
            ) {
            }

            public record ExistingCategoryRef(
            UUID id,
            String businessDomain,
            String code,
            String path,
            String latestName
            ) {
            }

            public record ExistingAttributeRef(
            UUID id,
            String businessDomain,
            String categoryCode,
            String key,
            String dataType,
            String lovKey,
            String structureHash
            ) {
            }

            public record ExistingEnumValueRef(
            String code,
            String name,
            String label,
            String attributeCode
            ) {
            }

            public record ExecutionPlanSnapshot(
                List<CategoryPlanItem> categories,
                List<AttributePlanItem> attributes,
                List<EnumPlanItem> enumOptions
            ) {
            }

            public record CategoryPlanItem(
                String sheetName,
                int rowNumber,
                String businessDomain,
                String excelReferenceCode,
                String categoryPath,
                String categoryName,
                String parentPath,
                String resolvedFinalCode,
                String resolvedFinalPath,
                String resolvedAction,
                String resolvedWriteMode,
                UUID existingCategoryId,
                String oldStateHash,
                String newStateHash,
                boolean shouldWrite,
                String codeMode
            ) {
            }

            public record AttributePlanItem(
                String sheetName,
                int rowNumber,
                String businessDomain,
                String categoryReferenceCode,
                String categoryName,
                String attributeReferenceCode,
                String attributeName,
                String attributeField,
                String description,
                String dataType,
                String unit,
                String defaultValue,
                Boolean required,
                Boolean unique,
                Boolean searchable,
                Boolean hidden,
                Boolean readOnly,
                BigDecimal minValue,
                BigDecimal maxValue,
                BigDecimal step,
                Integer precision,
                String trueLabel,
                String falseLabel,
                String resolvedCategoryCode,
                String resolvedFinalCode,
                String resolvedAction,
                String resolvedWriteMode,
                UUID existingAttributeId,
                String oldStructureHash,
                String newStructureHash,
                boolean shouldWrite,
                String codeMode
            ) {
            }

            public record EnumPlanItem(
                String sheetName,
                int rowNumber,
                String businessDomain,
                String categoryReferenceCode,
                String attributeReferenceCode,
                String optionReferenceCode,
                String optionName,
                String displayLabel,
                String resolvedCategoryCode,
                String resolvedAttributeCode,
                String resolvedFinalCode,
                String resolvedAction,
                String resolvedWriteMode,
                String existingOptionCode,
                String oldValueHash,
                String newValueHash,
                boolean shouldWrite,
                String codeMode
            ) {
            }

    public record ParsedCategoryRow(
            String sheetName,
            int rowNumber,
            String businessDomain,
            String excelReferenceCode,
            String categoryPath,
            String categoryName,
            String parentPath,
            List<WorkbookImportDryRunResponseDto.IssueDto> issues
    ) {
    }

    public record ParsedAttributeRow(
            String sheetName,
            int rowNumber,
            String categoryReferenceCode,
            String categoryName,
            String attributeReferenceCode,
            String attributeName,
            String attributeField,
            String description,
            String dataType,
            String unit,
            String defaultValue,
            Boolean required,
            Boolean unique,
            Boolean searchable,
            Boolean hidden,
            Boolean readOnly,
            BigDecimal minValue,
            BigDecimal maxValue,
            BigDecimal step,
            Integer precision,
            String trueLabel,
            String falseLabel,
            List<WorkbookImportDryRunResponseDto.IssueDto> issues
    ) {
    }

    public record ParsedEnumOptionRow(
            String sheetName,
            int rowNumber,
            String categoryReferenceCode,
            String attributeReferenceCode,
            String optionReferenceCode,
            String optionName,
            String displayLabel,
            List<WorkbookImportDryRunResponseDto.IssueDto> issues
    ) {
    }

    /**
     * Concurrency model:
     * Immutable job metadata is stored in final fields.
     * The mutable status DTO is guarded by this JobState instance monitor and must be accessed via
     * the synchronized helper methods below so callers observe a consistent snapshot.
     * Logs are stored in a CopyOnWriteArrayList to allow lock-free iteration for paging and SSE replay,
     * while updates that derive status fields from the log stream are coordinated under the same monitor.
     * Sequence allocation is handled independently through AtomicLong.
     */
    public static final class JobState {
        private final String jobId;
        private final String jobType;
        private final String operator;
        private final boolean atomic;
        private final String executionMode;
        private final WorkbookImportJobStatusDto status;
        private final CopyOnWriteArrayList<WorkbookImportLogEventDto> logs = new CopyOnWriteArrayList<>();
        private final AtomicLong sequence = new AtomicLong(0);
        private volatile WorkbookImportDryRunResponseDto dryRunResult;

        public JobState(String jobId,
                        String jobType,
                        String importSessionId,
                        String operator,
                        boolean atomic,
                        String executionMode,
                        WorkbookImportJobStatusDto status) {
            this.jobId = jobId;
            this.jobType = jobType;
            this.operator = operator;
            this.atomic = atomic;
            this.executionMode = executionMode;
            this.status = status;
            this.status.setJobType(jobType);
            this.status.setImportSessionId(importSessionId);
            this.status.setExecutionMode(executionMode);
        }

        public String getJobId() {
            return jobId;
        }

        public String getJobType() {
            return jobType;
        }

        public String getImportSessionId() {
            return readStatus(WorkbookImportJobStatusDto::getImportSessionId);
        }

        public void setImportSessionId(String importSessionId) {
            updateStatus(dto -> dto.setImportSessionId(importSessionId));
        }

        public String getOperator() {
            return operator;
        }

        public boolean isAtomic() {
            return atomic;
        }

        public String getExecutionMode() {
            return executionMode;
        }

        public synchronized <T> T readStatus(Function<WorkbookImportJobStatusDto, T> reader) {
            return reader.apply(status);
        }

        public synchronized void updateStatus(java.util.function.Consumer<WorkbookImportJobStatusDto> mutator) {
            mutator.accept(status);
        }

        public synchronized void appendLogAndUpdateStatus(WorkbookImportLogEventDto event,
                                                          BiConsumer<WorkbookImportJobStatusDto, List<WorkbookImportLogEventDto>> mutator) {
            logs.add(event);
            mutator.accept(status, new ArrayList<>(logs));
        }

        public List<WorkbookImportLogEventDto> snapshotLogs() {
            return new ArrayList<>(logs);
        }

        public long nextSequence() {
            return sequence.incrementAndGet();
        }

        public WorkbookImportDryRunResponseDto getDryRunResult() {
            return dryRunResult;
        }

        public void setDryRunResult(WorkbookImportDryRunResponseDto dryRunResult) {
            this.dryRunResult = dryRunResult;
        }
    }

    public static WorkbookImportJobStatusDto newJobStatus(String jobId,
                                                           String importSessionId,
                                                           int categoryTotal,
                                                           int attributeTotal,
                                                           int enumTotal) {
        WorkbookImportJobStatusDto dto = new WorkbookImportJobStatusDto();
        dto.setJobId(jobId);
        dto.setImportSessionId(importSessionId);
        dto.setStatus(STATUS_QUEUED);
        dto.setCurrentStage(STAGE_PREPARING);
        dto.setTotalRows(categoryTotal + attributeTotal + enumTotal);
        dto.setProcessedRows(0);
        dto.setOverallPercent(0);
        dto.setStagePercent(0);
        dto.setStartedAt(OffsetDateTime.now());
        dto.setUpdatedAt(dto.getStartedAt());
        dto.setProgress(newProgress(categoryTotal, attributeTotal, enumTotal));
        dto.setLatestLogs(new ArrayList<>());
        return dto;
    }

    public static WorkbookImportJobStatusDto.ProgressDto newProgress(int categoryTotal,
                                                                     int attributeTotal,
                                                                     int enumTotal) {
        WorkbookImportJobStatusDto.ProgressDto progress = new WorkbookImportJobStatusDto.ProgressDto();
        progress.setCategories(newEntityProgress(categoryTotal));
        progress.setAttributes(newEntityProgress(attributeTotal));
        progress.setEnumOptions(newEntityProgress(enumTotal));
        return progress;
    }

    public static WorkbookImportJobStatusDto.EntityProgressDto newEntityProgress(int total) {
        WorkbookImportJobStatusDto.EntityProgressDto dto = new WorkbookImportJobStatusDto.EntityProgressDto();
        dto.setTotal(total);
        dto.setProcessed(0);
        dto.setCreated(0);
        dto.setUpdated(0);
        dto.setSkipped(0);
        dto.setFailed(0);
        return dto;
    }
}