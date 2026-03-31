package com.plm.attribute.version.service.workbook;

import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunOptionsDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportLogEventDto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class WorkbookImportSupport {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_PREPARING = "PREPARING";
    public static final String STATUS_IMPORTING_CATEGORIES = "IMPORTING_CATEGORIES";
    public static final String STATUS_IMPORTING_ATTRIBUTES = "IMPORTING_ATTRIBUTES";
    public static final String STATUS_IMPORTING_ENUM_OPTIONS = "IMPORTING_ENUM_OPTIONS";
    public static final String STATUS_FINALIZING = "FINALIZING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
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
            OffsetDateTime createdAt
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
        private final String importSessionId;
        private final String operator;
        private final boolean atomic;
        private final WorkbookImportJobStatusDto status;
        private final CopyOnWriteArrayList<WorkbookImportLogEventDto> logs = new CopyOnWriteArrayList<>();
        private final AtomicLong sequence = new AtomicLong(0);

        public JobState(String jobId, String importSessionId, String operator, boolean atomic, WorkbookImportJobStatusDto status) {
            this.jobId = jobId;
            this.importSessionId = importSessionId;
            this.operator = operator;
            this.atomic = atomic;
            this.status = status;
        }

        public String getJobId() {
            return jobId;
        }

        public String getImportSessionId() {
            return importSessionId;
        }

        public String getOperator() {
            return operator;
        }

        public boolean isAtomic() {
            return atomic;
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