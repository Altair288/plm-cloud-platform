package com.plm.attribute.version.service.workbook.export;

import com.plm.common.api.dto.exports.workbook.WorkbookExportJobResultDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobStatusDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportLogEventDto;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class WorkbookExportSupport {

    public static final String MODULE_CATEGORY = "CATEGORY";
    public static final String MODULE_ATTRIBUTE = "ATTRIBUTE";
    public static final String MODULE_ENUM_OPTION = "ENUM_OPTION";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RESOLVING_SCOPE = "RESOLVING_SCOPE";
    public static final String STATUS_LOADING_CATEGORIES = "LOADING_CATEGORIES";
    public static final String STATUS_LOADING_ATTRIBUTES = "LOADING_ATTRIBUTES";
    public static final String STATUS_LOADING_ENUM_OPTIONS = "LOADING_ENUM_OPTIONS";
    public static final String STATUS_BUILDING_WORKBOOK = "BUILDING_WORKBOOK";
    public static final String STATUS_STORING_FILE = "STORING_FILE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELED = "CANCELED";

    public static final String STAGE_RESOLVING_SCOPE = "RESOLVING_SCOPE";
    public static final String STAGE_LOADING_CATEGORIES = "LOADING_CATEGORIES";
    public static final String STAGE_LOADING_ATTRIBUTES = "LOADING_ATTRIBUTES";
    public static final String STAGE_LOADING_ENUM_OPTIONS = "LOADING_ENUM_OPTIONS";
    public static final String STAGE_BUILDING_WORKBOOK = "BUILDING_WORKBOOK";
    public static final String STAGE_STORING_FILE = "STORING_FILE";

    private WorkbookExportSupport() {
    }

    public record ExportDataBundle(
            List<java.util.Map<String, Object>> categories,
            List<java.util.Map<String, Object>> attributes,
            List<java.util.Map<String, Object>> enumOptions) {
    }

    public record ExportArtifact(
            byte[] content,
            WorkbookExportJobResultDto result) {
    }

    public static WorkbookExportJobStatusDto newJobStatus(String jobId,
                                                           String businessDomain,
                                                           String fileName) {
        WorkbookExportJobStatusDto dto = new WorkbookExportJobStatusDto();
        dto.setJobId(jobId);
        dto.setBusinessDomain(businessDomain);
        dto.setFileName(fileName);
        dto.setStatus(STATUS_QUEUED);
        dto.setCurrentStage(STAGE_RESOLVING_SCOPE);
        dto.setOverallPercent(0);
        dto.setStagePercent(0);
        dto.setCreatedAt(OffsetDateTime.now());
        dto.setUpdatedAt(dto.getCreatedAt());
        dto.setProgress(newProgress());
        dto.setLatestLogs(new ArrayList<>());
        dto.setWarnings(new ArrayList<>());
        return dto;
    }

    public static WorkbookExportJobStatusDto.ProgressDto newProgress() {
        WorkbookExportJobStatusDto.ProgressDto dto = new WorkbookExportJobStatusDto.ProgressDto();
        dto.setCategories(newModuleProgress());
        dto.setAttributes(newModuleProgress());
        dto.setEnumOptions(newModuleProgress());
        return dto;
    }

    public static WorkbookExportJobStatusDto.ModuleProgressDto newModuleProgress() {
        WorkbookExportJobStatusDto.ModuleProgressDto dto = new WorkbookExportJobStatusDto.ModuleProgressDto();
        dto.setTotal(0);
        dto.setProcessed(0);
        dto.setExported(0);
        dto.setFailed(0);
        return dto;
    }

    public static final class JobState {
        private final String jobId;
        private final WorkbookExportJobStatusDto status;
        private final CopyOnWriteArrayList<WorkbookExportLogEventDto> logs = new CopyOnWriteArrayList<>();
        private final AtomicLong sequence = new AtomicLong(0);
        private volatile WorkbookExportJobResultDto result;
        private volatile byte[] fileContent;
        private volatile boolean cancelRequested;

        public JobState(String jobId, WorkbookExportJobStatusDto status) {
            this.jobId = jobId;
            this.status = status;
        }

        public String getJobId() {
            return jobId;
        }

        public synchronized <T> T readStatus(Function<WorkbookExportJobStatusDto, T> reader) {
            return reader.apply(status);
        }

        public synchronized void updateStatus(java.util.function.Consumer<WorkbookExportJobStatusDto> mutator) {
            mutator.accept(status);
        }

        public synchronized void appendLogAndUpdateStatus(WorkbookExportLogEventDto event,
                                                          BiConsumer<WorkbookExportJobStatusDto, List<WorkbookExportLogEventDto>> mutator) {
            logs.add(event);
            mutator.accept(status, logs);
        }

        public List<WorkbookExportLogEventDto> snapshotLogs() {
            return new ArrayList<>(logs);
        }

        public long nextSequence() {
            return sequence.incrementAndGet();
        }

        public WorkbookExportJobResultDto getResult() {
            return result;
        }

        public void setResult(WorkbookExportJobResultDto result) {
            this.result = result;
        }

        public byte[] getFileContent() {
            return fileContent;
        }

        public void setFileContent(byte[] fileContent) {
            this.fileContent = fileContent;
        }

        public boolean isCancelRequested() {
            return cancelRequested;
        }

        public void requestCancel() {
            this.cancelRequested = true;
        }
    }
}