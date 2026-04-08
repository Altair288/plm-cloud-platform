package com.plm.attribute.version.service.workbook.export;

import com.plm.attribute.version.service.workbook.WorkbookImportProperties;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobResultDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobStatusDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportLogEventDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportLogPageDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartRequestDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

@Service
public class WorkbookExportRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(WorkbookExportRuntimeService.class);
    private static final long DEFAULT_EMITTER_TIMEOUT_MILLIS = 1_800_000L;

    private final WorkbookImportProperties properties;
    private final Map<String, WorkbookExportSupport.JobState> jobs = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public WorkbookExportRuntimeService(WorkbookImportProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public WorkbookExportSupport.JobState createJob(WorkbookExportStartRequestDto request,
                                                    String fileName) {
        String jobId = UUID.randomUUID().toString();
        WorkbookExportJobStatusDto status = WorkbookExportSupport.newJobStatus(jobId, request.getBusinessDomain(), fileName);
        WorkbookExportSupport.JobState job = new WorkbookExportSupport.JobState(jobId, status);
        jobs.put(jobId, job);
        emitProgress(job, "progress");
        return job;
    }

    public WorkbookExportJobStatusDto getJobStatus(String jobId) {
        return snapshotStatus(getJob(jobId));
    }

    public WorkbookExportJobResultDto getResult(String jobId) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        WorkbookExportJobResultDto result = job.getResult();
        if (result == null) {
            throw new IllegalStateException("workbook export result is not ready: jobId=" + jobId);
        }
        return snapshotResult(result);
    }

    public byte[] getFileContent(String jobId) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        WorkbookExportJobResultDto result = job.getResult();
        if (result != null && result.getFile() != null && result.getFile().getExpiresAt() != null
                && result.getFile().getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("workbook export file expired: jobId=" + jobId);
        }
        byte[] bytes = job.getFileContent();
        if (bytes == null) {
            throw new IllegalStateException("workbook export file is not ready: jobId=" + jobId);
        }
        return bytes.clone();
    }

    public void updateStage(String jobId,
                            String status,
                            String currentStage,
                            Integer overallPercent,
                            Integer stagePercent) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        job.updateStatus(dto -> {
            if (dto.getStartedAt() == null && !WorkbookExportSupport.STATUS_QUEUED.equalsIgnoreCase(status)) {
                dto.setStartedAt(OffsetDateTime.now());
            }
            dto.setStatus(status);
            dto.setCurrentStage(currentStage);
            dto.setOverallPercent(overallPercent);
            dto.setStagePercent(stagePercent);
            dto.setUpdatedAt(OffsetDateTime.now());
        });
        emitProgress(job, "progress");
    }

    public void setModuleProgress(String jobId,
                                  String moduleKey,
                                  int total,
                                  int processed,
                                  int exported,
                                  int failed) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        job.updateStatus(dto -> {
            WorkbookExportJobStatusDto.ModuleProgressDto moduleProgress = moduleProgress(dto, moduleKey);
            moduleProgress.setTotal(total);
            moduleProgress.setProcessed(processed);
            moduleProgress.setExported(exported);
            moduleProgress.setFailed(failed);
            dto.setUpdatedAt(OffsetDateTime.now());
        });
        emitProgress(job, "progress");
    }

    public WorkbookExportLogEventDto appendLog(String jobId,
                                               Consumer<WorkbookExportLogEventDto> mutator) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        WorkbookExportLogEventDto event = new WorkbookExportLogEventDto();
        long sequence = job.nextSequence();
        event.setSequence(sequence);
        event.setCursor(Long.toString(sequence));
        event.setTimestamp(OffsetDateTime.now());
        mutator.accept(event);
        job.appendLogAndUpdateStatus(event, (status, logSnapshot) -> {
            status.setLatestLogCursor(event.getCursor());
            int fromIndex = Math.max(0, logSnapshot.size() - 20);
            status.setLatestLogs(new ArrayList<>(logSnapshot.subList(fromIndex, logSnapshot.size())));
            status.setUpdatedAt(OffsetDateTime.now());
        });
        emit(job, "log", event);
        return event;
    }

    public void complete(String jobId,
                         WorkbookExportJobResultDto result,
                         byte[] fileContent) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        OffsetDateTime now = OffsetDateTime.now();
        result.setJobId(jobId);
        result.setStatus(WorkbookExportSupport.STATUS_COMPLETED);
        result.setCompletedAt(now);
        job.setResult(result);
        job.setFileContent(fileContent == null ? null : fileContent.clone());
        job.updateStatus(dto -> {
            dto.setStatus(WorkbookExportSupport.STATUS_COMPLETED);
            dto.setCurrentStage(WorkbookExportSupport.STAGE_STORING_FILE);
            dto.setOverallPercent(100);
            dto.setStagePercent(100);
            if (dto.getStartedAt() == null) {
                dto.setStartedAt(now);
            }
            dto.setCompletedAt(now);
            dto.setUpdatedAt(now);
        });
        emitProgress(job, "completed");
    }

    public WorkbookExportJobStatusDto cancelJob(String jobId) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        WorkbookExportJobStatusDto current = snapshotStatus(job);
        if (isTerminalStatus(current.getStatus())) {
            return current;
        }
        OffsetDateTime now = OffsetDateTime.now();
        job.requestCancel();
        job.updateStatus(dto -> {
            dto.setStatus(WorkbookExportSupport.STATUS_CANCELED);
            dto.setCompletedAt(now);
            dto.setUpdatedAt(now);
            if (dto.getStartedAt() == null) {
                dto.setStartedAt(now);
            }
        });
        appendLog(jobId, event -> {
            event.setLevel("WARN");
            event.setStage(job.readStatus(WorkbookExportJobStatusDto::getCurrentStage));
            event.setEventType("JOB_CANCELED");
            event.setCode("WORKBOOK_EXPORT_CANCELED");
            event.setMessage("workbook export job canceled");
        });
        emitProgress(job, "canceled");
        return snapshotStatus(job);
    }

    public void ensureNotCanceled(String jobId) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        if (job.isCancelRequested() || WorkbookExportSupport.STATUS_CANCELED.equalsIgnoreCase(job.readStatus(WorkbookExportJobStatusDto::getStatus))) {
            throw new CancellationException("workbook export canceled: jobId=" + jobId);
        }
    }

    public void fail(String jobId,
                     Exception ex) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        OffsetDateTime now = OffsetDateTime.now();
        job.updateStatus(dto -> {
            dto.setStatus(WorkbookExportSupport.STATUS_FAILED);
            dto.setUpdatedAt(now);
            dto.setCompletedAt(now);
        });
        appendLog(jobId, event -> {
            event.setLevel("ERROR");
            event.setStage(job.readStatus(WorkbookExportJobStatusDto::getCurrentStage));
            event.setEventType("JOB_FAILED");
            event.setCode("WORKBOOK_EXPORT_FAILED");
            event.setMessage(ex.getMessage() == null ? "workbook export failed" : ex.getMessage());
        });
        emitProgress(job, "failed");
    }

    public WorkbookExportLogPageDto getLogs(String jobId,
                                            String cursor,
                                            Integer limit,
                                            String level,
                                            String stage,
                                            String moduleKey) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        long minSequence = parseCursor(cursor);
        int pageSize = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<WorkbookExportLogEventDto> filtered = job.snapshotLogs().stream()
                .filter(item -> item.getSequence() != null && item.getSequence() > minSequence)
                .filter(item -> level == null || level.isBlank() || level.equalsIgnoreCase(item.getLevel()))
                .filter(item -> stage == null || stage.isBlank() || stage.equalsIgnoreCase(item.getStage()))
                .filter(item -> moduleKey == null || moduleKey.isBlank() || moduleKey.equalsIgnoreCase(item.getModuleKey()))
                .sorted(Comparator.comparingLong(WorkbookExportLogEventDto::getSequence))
                .limit(pageSize)
                .toList();
        WorkbookExportLogPageDto page = new WorkbookExportLogPageDto();
        page.setJobId(jobId);
        page.setItems(filtered);
        page.setNextCursor(filtered.isEmpty() ? cursor : filtered.get(filtered.size() - 1).getCursor());
        return page;
    }

    public SseEmitter subscribe(String jobId) {
        WorkbookExportSupport.JobState job = getJob(jobId);
        SseEmitter emitter = new SseEmitter(resolveEmitterTimeoutMillis());
        emitters.computeIfAbsent(jobId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(ex -> removeEmitter(jobId, emitter));
        emitProgress(job, "progress", emitter);
        return emitter;
    }

    @Scheduled(fixedDelayString = "#{@workbookImportProperties.runtime.cleanupIntervalMillis}")
    void cleanupExpiredState() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            long retentionMillis = properties.getRuntime().getTerminalJobRetentionMillis();
            List<String> expiredJobIds = jobs.entrySet().stream()
                    .filter(entry -> isTerminalStatus(snapshotStatus(entry.getValue()).getStatus()))
                    .filter(entry -> isOlderThan(snapshotStatus(entry.getValue()).getUpdatedAt(), retentionMillis, now))
                    .map(Map.Entry::getKey)
                    .toList();
            for (String jobId : expiredJobIds) {
                jobs.remove(jobId);
                completeAndRemoveEmitters(jobId);
            }
            removeOrphanedEmitters();
        } catch (RuntimeException ex) {
            log.error("Failed to cleanup workbook export runtime state", ex);
        }
    }

    private WorkbookExportSupport.JobState getJob(String jobId) {
        WorkbookExportSupport.JobState job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("workbook export job not found: jobId=" + jobId);
        }
        return job;
    }

    private WorkbookExportJobStatusDto snapshotStatus(WorkbookExportSupport.JobState job) {
        return job.readStatus(source -> {
            WorkbookExportJobStatusDto copy = new WorkbookExportJobStatusDto();
            copy.setJobId(source.getJobId());
            copy.setBusinessDomain(source.getBusinessDomain());
            copy.setStatus(source.getStatus());
            copy.setCurrentStage(source.getCurrentStage());
            copy.setFileName(source.getFileName());
            copy.setOverallPercent(source.getOverallPercent());
            copy.setStagePercent(source.getStagePercent());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setStartedAt(source.getStartedAt());
            copy.setUpdatedAt(source.getUpdatedAt());
            copy.setCompletedAt(source.getCompletedAt());
            copy.setLatestLogCursor(source.getLatestLogCursor());
            copy.setLatestLogs(source.getLatestLogs() == null ? List.of() : new ArrayList<>(source.getLatestLogs()));
            copy.setWarnings(source.getWarnings() == null ? List.of() : new ArrayList<>(source.getWarnings()));
            if (source.getProgress() != null) {
                WorkbookExportJobStatusDto.ProgressDto progress = new WorkbookExportJobStatusDto.ProgressDto();
                progress.setCategories(copyModuleProgress(source.getProgress().getCategories()));
                progress.setAttributes(copyModuleProgress(source.getProgress().getAttributes()));
                progress.setEnumOptions(copyModuleProgress(source.getProgress().getEnumOptions()));
                copy.setProgress(progress);
            }
            return copy;
        });
    }

    private WorkbookExportJobResultDto snapshotResult(WorkbookExportJobResultDto source) {
        WorkbookExportJobResultDto copy = new WorkbookExportJobResultDto();
        copy.setJobId(source.getJobId());
        copy.setStatus(source.getStatus());
        copy.setResolvedRequest(source.getResolvedRequest());
        copy.setWarnings(source.getWarnings() == null ? List.of() : new ArrayList<>(source.getWarnings()));
        copy.setCompletedAt(source.getCompletedAt());
        if (source.getSummary() != null) {
            WorkbookExportJobResultDto.SummaryDto summary = new WorkbookExportJobResultDto.SummaryDto();
            summary.setCategories(copySummary(source.getSummary().getCategories()));
            summary.setAttributes(copySummary(source.getSummary().getAttributes()));
            summary.setEnumOptions(copySummary(source.getSummary().getEnumOptions()));
            copy.setSummary(summary);
        }
        if (source.getFile() != null) {
            WorkbookExportJobResultDto.FileDto file = new WorkbookExportJobResultDto.FileDto();
            file.setFileName(source.getFile().getFileName());
            file.setContentType(source.getFile().getContentType());
            file.setSize(source.getFile().getSize());
            file.setChecksum(source.getFile().getChecksum());
            file.setExpiresAt(source.getFile().getExpiresAt());
            copy.setFile(file);
        }
        return copy;
    }

    private WorkbookExportJobResultDto.ModuleSummaryDto copySummary(WorkbookExportJobResultDto.ModuleSummaryDto source) {
        if (source == null) {
            return null;
        }
        WorkbookExportJobResultDto.ModuleSummaryDto copy = new WorkbookExportJobResultDto.ModuleSummaryDto();
        copy.setSheetName(source.getSheetName());
        copy.setTotalRows(source.getTotalRows());
        copy.setExportedRows(source.getExportedRows());
        return copy;
    }

    private WorkbookExportJobStatusDto.ModuleProgressDto copyModuleProgress(WorkbookExportJobStatusDto.ModuleProgressDto source) {
        if (source == null) {
            return null;
        }
        WorkbookExportJobStatusDto.ModuleProgressDto copy = new WorkbookExportJobStatusDto.ModuleProgressDto();
        copy.setTotal(source.getTotal());
        copy.setProcessed(source.getProcessed());
        copy.setExported(source.getExported());
        copy.setFailed(source.getFailed());
        return copy;
    }

    private WorkbookExportJobStatusDto.ModuleProgressDto moduleProgress(WorkbookExportJobStatusDto dto,
                                                                        String moduleKey) {
        return switch (moduleKey) {
            case WorkbookExportSupport.MODULE_CATEGORY -> dto.getProgress().getCategories();
            case WorkbookExportSupport.MODULE_ATTRIBUTE -> dto.getProgress().getAttributes();
            case WorkbookExportSupport.MODULE_ENUM_OPTION -> dto.getProgress().getEnumOptions();
            default -> throw new IllegalArgumentException("unsupported workbook export moduleKey: " + moduleKey);
        };
    }

    private long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid workbook export log cursor: " + cursor, ex);
        }
    }

    private boolean isTerminalStatus(String status) {
        return WorkbookExportSupport.STATUS_COMPLETED.equalsIgnoreCase(status)
                || WorkbookExportSupport.STATUS_FAILED.equalsIgnoreCase(status)
                || WorkbookExportSupport.STATUS_CANCELED.equalsIgnoreCase(status);
    }

    private boolean isOlderThan(OffsetDateTime value,
                                long retentionMillis,
                                OffsetDateTime now) {
        return value != null && value.plusNanos(retentionMillis * 1_000_000L).isBefore(now);
    }

    private long resolveEmitterTimeoutMillis() {
        long configured = properties.getRuntime().getEmitterTimeoutMillis();
        return configured > 0 ? configured : DEFAULT_EMITTER_TIMEOUT_MILLIS;
    }

    private void emitProgress(WorkbookExportSupport.JobState job,
                              String eventName) {
        emitProgress(job, eventName, null);
    }

    private void emitProgress(WorkbookExportSupport.JobState job,
                              String eventName,
                              SseEmitter target) {
        emit(job, eventName, snapshotStatus(job), target);
    }

    private void emit(WorkbookExportSupport.JobState job,
                      String eventName,
                      Object data) {
        emit(job, eventName, data, null);
    }

    private void emit(WorkbookExportSupport.JobState job,
                      String eventName,
                      Object data,
                      SseEmitter target) {
        if (target != null) {
            send(job.getJobId(), target, eventName, data);
            return;
        }
        List<SseEmitter> currentEmitters = emitters.get(job.getJobId());
        if (currentEmitters == null || currentEmitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : currentEmitters) {
            send(job.getJobId(), emitter, eventName, data);
        }
    }

    private void send(String jobId,
                      SseEmitter emitter,
                      String eventName,
                      Object data) {
        try {
            emitter.send(SseEmitter.event().name(Objects.requireNonNull(eventName, "eventName")).data(Objects.requireNonNull(data, "data")));
        } catch (IOException ex) {
            removeEmitter(jobId, emitter);
        }
    }

    private void removeEmitter(String jobId,
                               SseEmitter emitter) {
        List<SseEmitter> currentEmitters = emitters.get(jobId);
        if (currentEmitters != null) {
            currentEmitters.remove(emitter);
        }
    }

    private void removeOrphanedEmitters() {
        Set<String> activeJobIds = new HashSet<>(jobs.keySet());
        List<String> orphaned = emitters.keySet().stream().filter(jobId -> !activeJobIds.contains(jobId)).toList();
        orphaned.forEach(this::completeAndRemoveEmitters);
    }

    private void completeAndRemoveEmitters(String jobId) {
        List<SseEmitter> removed = emitters.remove(jobId);
        if (removed == null) {
            return;
        }
        for (SseEmitter emitter : removed) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
            }
        }
    }
}