package com.plm.attribute.version.service.workbook;

import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportLogEventDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportLogPageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
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
import java.util.function.Consumer;

@Service
public class WorkbookImportRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(WorkbookImportRuntimeService.class);
    private static final long DEFAULT_EMITTER_TIMEOUT_MILLIS = 1_800_000L;

    private final WorkbookImportProperties properties;
    private final Map<String, WorkbookImportSupport.ImportSessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, WorkbookImportSupport.JobState> jobs = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public WorkbookImportRuntimeService(WorkbookImportProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public WorkbookImportSupport.ImportSessionState saveSession(WorkbookImportSupport.ImportSessionState session) {
        sessions.put(session.importSessionId(), session);
        return session;
    }

    public WorkbookImportSupport.ImportSessionState getSession(String importSessionId) {
        WorkbookImportSupport.ImportSessionState session = sessions.get(importSessionId);
        if (session == null) {
            throw new IllegalArgumentException("workbook import session not found: importSessionId=" + importSessionId);
        }
        return session;
    }

    public WorkbookImportDryRunResponseDto getSessionResponse(String importSessionId) {
        return getSession(importSessionId).response();
    }

    public WorkbookImportSupport.JobState createJob(WorkbookImportSupport.ImportSessionState session,
                                                    String operator,
                                                    boolean atomic) {
        String jobId = UUID.randomUUID().toString();
        WorkbookImportJobStatusDto status = WorkbookImportSupport.newJobStatus(
                jobId,
                session.importSessionId(),
                session.categories().size(),
                session.attributes().size(),
                session.enumOptions().size());
        WorkbookImportSupport.JobState state = new WorkbookImportSupport.JobState(jobId, session.importSessionId(), operator, atomic, status);
        jobs.put(jobId, state);
        emitProgress(state, "progress");
        return state;
    }

    public WorkbookImportSupport.JobState getJob(String jobId) {
        WorkbookImportSupport.JobState job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("workbook import job not found: jobId=" + jobId);
        }
        return job;
    }

    public WorkbookImportJobStatusDto getJobStatus(String jobId) {
        WorkbookImportSupport.JobState job = getJob(jobId);
        return snapshotStatus(job);
    }

    public void mutateStatus(String jobId, Consumer<WorkbookImportJobStatusDto> mutator) {
        WorkbookImportSupport.JobState job = getJob(jobId);
        job.updateStatus(dto -> {
            mutator.accept(dto);
            dto.setUpdatedAt(OffsetDateTime.now());
            recalculateOverallPercent(dto);
        });
        emitProgress(job, "progress");
    }

    public void setStage(String jobId, String status, String stage) {
        mutateStatus(jobId, dto -> {
            dto.setStatus(status);
            dto.setCurrentStage(stage);
            dto.setStagePercent(0);
        });
        WorkbookImportSupport.JobState job = getJob(jobId);
        emit(job, "stage-changed", Map.of(
                "jobId", jobId,
                "status", status,
                "currentStage", stage,
                "updatedAt", OffsetDateTime.now().toString()));
    }

    public void completeJob(String jobId) {
        mutateStatus(jobId, dto -> {
            dto.setStatus(WorkbookImportSupport.STATUS_COMPLETED);
            dto.setCurrentStage(WorkbookImportSupport.STAGE_FINALIZING);
            dto.setStagePercent(100);
            dto.setOverallPercent(100);
        });
        WorkbookImportSupport.JobState job = getJob(jobId);
        emit(job, "completed", Map.of(
                "jobId", jobId,
                "status", WorkbookImportSupport.STATUS_COMPLETED));
    }

    public void failJob(String jobId, String message) {
        mutateStatus(jobId, dto -> dto.setStatus(WorkbookImportSupport.STATUS_FAILED));
        WorkbookImportSupport.JobState job = getJob(jobId);
        emit(job, "failed", Map.of(
                "jobId", jobId,
                "status", WorkbookImportSupport.STATUS_FAILED,
                "message", message == null ? "workbook import failed" : message));
    }

    public WorkbookImportLogEventDto appendLog(String jobId, Consumer<WorkbookImportLogEventDto> mutator) {
        WorkbookImportSupport.JobState job = getJob(jobId);
        WorkbookImportLogEventDto event = new WorkbookImportLogEventDto();
        long sequence = job.nextSequence();
        event.setSequence(sequence);
        event.setCursor(Long.toString(sequence));
        event.setTimestamp(OffsetDateTime.now());
        mutator.accept(event);
        job.appendLogAndUpdateStatus(event, (status, logSnapshot) -> {
            status.setLatestLogCursor(event.getCursor());
            List<WorkbookImportLogEventDto> latestLogs = logSnapshot;
            int fromIndex = Math.max(0, latestLogs.size() - 20);
            status.setLatestLogs(new ArrayList<>(latestLogs.subList(fromIndex, latestLogs.size())));
            status.setUpdatedAt(OffsetDateTime.now());
        });

        emit(job, "log", event);
        return event;
    }

    public WorkbookImportLogPageDto getLogs(String jobId,
                                            String cursor,
                                            Integer limit,
                                            String level,
                                            String stage,
                                            String sheetName,
                                            Integer rowNumber) {
        WorkbookImportSupport.JobState job = getJob(jobId);
        long minSequence = parseCursor(cursor);
        int pageSize = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<WorkbookImportLogEventDto> filtered = job.snapshotLogs().stream()
                .filter(item -> item.getSequence() != null && item.getSequence() > minSequence)
                .filter(item -> level == null || level.isBlank() || level.equalsIgnoreCase(item.getLevel()))
                .filter(item -> stage == null || stage.isBlank() || stage.equalsIgnoreCase(item.getStage()))
                .filter(item -> sheetName == null || sheetName.isBlank() || sheetName.equalsIgnoreCase(item.getSheetName()))
                .filter(item -> rowNumber == null || rowNumber.equals(item.getRowNumber()))
                .sorted(Comparator.comparingLong(WorkbookImportLogEventDto::getSequence))
                .limit(pageSize)
                .toList();

        WorkbookImportLogPageDto page = new WorkbookImportLogPageDto();
        page.setJobId(jobId);
        page.setItems(filtered);
        if (!filtered.isEmpty()) {
            page.setNextCursor(filtered.get(filtered.size() - 1).getCursor());
        } else {
            page.setNextCursor(cursor);
        }
        return page;
    }

    public SseEmitter subscribe(String jobId) {
        WorkbookImportSupport.JobState job = getJob(jobId);
        SseEmitter emitter = new SseEmitter(resolveEmitterTimeoutMillis());
        emitters.computeIfAbsent(jobId, key -> new CopyOnWriteArrayList<>()).add(emitter);
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
            removeExpiredJobs(now);
            removeExpiredSessions(now);
            removeOrphanedEmitters();
        } catch (RuntimeException ex) {
            log.error("Failed to cleanup workbook import runtime state", ex);
        }
    }

    private void emitProgress(WorkbookImportSupport.JobState job, String eventName) {
        emitProgress(job, eventName, null);
    }

    private void emitProgress(WorkbookImportSupport.JobState job, String eventName, SseEmitter target) {
        WorkbookImportJobStatusDto snapshot = snapshotStatus(job);
        emit(job, eventName, snapshot, target);
    }

    private void emit(WorkbookImportSupport.JobState job, String eventName, Object data) {
        emit(job, eventName, data, null);
    }

    private void emit(WorkbookImportSupport.JobState job, String eventName, Object data, SseEmitter target) {
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

    private void send(String jobId, SseEmitter emitter, String eventName, Object data) {
        try {
            String nonNullEventName = Objects.requireNonNull(eventName, "eventName");
            Object nonNullData = Objects.requireNonNull(data, "data");
            emitter.send(SseEmitter.event().name(nonNullEventName).data(nonNullData));
        } catch (IOException ex) {
            removeEmitter(jobId, emitter);
        }
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        List<SseEmitter> currentEmitters = emitters.get(jobId);
        if (currentEmitters != null) {
            currentEmitters.remove(emitter);
        }
    }

    private void removeExpiredJobs(OffsetDateTime now) {
        long retentionMillis = properties.getRuntime().getTerminalJobRetentionMillis();
        List<String> expiredJobIds = jobs.entrySet().stream()
                .filter(entry -> {
                    WorkbookImportJobStatusDto status = snapshotStatus(entry.getValue());
                    return isTerminalStatus(status.getStatus())
                            && isOlderThan(status.getUpdatedAt(), retentionMillis, now);
                })
                .map(Map.Entry::getKey)
                .toList();
        for (String jobId : expiredJobIds) {
            jobs.remove(jobId);
            completeAndRemoveEmitters(jobId);
        }
    }

    private void removeExpiredSessions(OffsetDateTime now) {
        long retentionMillis = properties.getRuntime().getSessionRetentionMillis();
        Set<String> activeSessionIds = new HashSet<>();
        jobs.values().forEach(job -> activeSessionIds.add(job.getImportSessionId()));
        sessions.entrySet().removeIf(entry -> !activeSessionIds.contains(entry.getKey())
                && isOlderThan(entry.getValue().createdAt(), retentionMillis, now));
    }

    private void removeOrphanedEmitters() {
        List<String> orphanedJobIds = emitters.keySet().stream()
                .filter(jobId -> !jobs.containsKey(jobId))
                .toList();
        orphanedJobIds.forEach(this::completeAndRemoveEmitters);
    }

    private void completeAndRemoveEmitters(String jobId) {
        List<SseEmitter> currentEmitters = emitters.remove(jobId);
        if (currentEmitters == null) {
            return;
        }
        currentEmitters.forEach(SseEmitter::complete);
    }

    private boolean isTerminalStatus(String status) {
        return WorkbookImportSupport.STATUS_COMPLETED.equalsIgnoreCase(status)
                || WorkbookImportSupport.STATUS_FAILED.equalsIgnoreCase(status);
    }

    private boolean isOlderThan(OffsetDateTime timestamp, long retentionMillis, OffsetDateTime now) {
        if (retentionMillis <= 0) {
            return true;
        }
        if (timestamp == null) {
            return true;
        }
        return timestamp.isBefore(now.minus(Duration.ofMillis(retentionMillis)));
    }

    private long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid cursor: " + cursor);
        }
    }

    private void recalculateOverallPercent(WorkbookImportJobStatusDto status) {
        WorkbookImportJobStatusDto.ProgressDto progress = status.getProgress();
        if (progress == null) {
            return;
        }
        int total = safeTotal(progress.getCategories()) + safeTotal(progress.getAttributes()) + safeTotal(progress.getEnumOptions());
        int processed = safeProcessed(progress.getCategories()) + safeProcessed(progress.getAttributes()) + safeProcessed(progress.getEnumOptions());
        if (total <= 0) {
            status.setOverallPercent(100);
            return;
        }
        status.setOverallPercent(Math.max(0, Math.min(100, (processed * 100) / total)));
    }

    private int safeTotal(WorkbookImportJobStatusDto.EntityProgressDto dto) {
        return dto == null || dto.getTotal() == null ? 0 : dto.getTotal();
    }

    private int safeProcessed(WorkbookImportJobStatusDto.EntityProgressDto dto) {
        return dto == null || dto.getProcessed() == null ? 0 : dto.getProcessed();
    }

    private long resolveEmitterTimeoutMillis() {
        long configuredTimeoutMillis = properties.getRuntime().getEmitterTimeoutMillis();
        return configuredTimeoutMillis > 0 ? configuredTimeoutMillis : DEFAULT_EMITTER_TIMEOUT_MILLIS;
    }

    private WorkbookImportJobStatusDto snapshotStatus(WorkbookImportSupport.JobState job) {
        return job.readStatus(this::copyStatus);
    }

    private WorkbookImportJobStatusDto copyStatus(WorkbookImportJobStatusDto source) {
        WorkbookImportJobStatusDto target = new WorkbookImportJobStatusDto();
        target.setJobId(source.getJobId());
        target.setImportSessionId(source.getImportSessionId());
        target.setStatus(source.getStatus());
        target.setCurrentStage(source.getCurrentStage());
        target.setOverallPercent(source.getOverallPercent());
        target.setStagePercent(source.getStagePercent());
        target.setStartedAt(source.getStartedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        target.setLatestLogCursor(source.getLatestLogCursor());
        target.setLatestLogs(source.getLatestLogs() == null ? List.of() : new ArrayList<>(source.getLatestLogs()));
        if (source.getProgress() != null) {
            WorkbookImportJobStatusDto.ProgressDto progress = new WorkbookImportJobStatusDto.ProgressDto();
            progress.setCategories(copyEntityProgress(source.getProgress().getCategories()));
            progress.setAttributes(copyEntityProgress(source.getProgress().getAttributes()));
            progress.setEnumOptions(copyEntityProgress(source.getProgress().getEnumOptions()));
            target.setProgress(progress);
        }
        return target;
    }

    private WorkbookImportJobStatusDto.EntityProgressDto copyEntityProgress(WorkbookImportJobStatusDto.EntityProgressDto source) {
        if (source == null) {
            return null;
        }
        WorkbookImportJobStatusDto.EntityProgressDto target = new WorkbookImportJobStatusDto.EntityProgressDto();
        target.setTotal(source.getTotal());
        target.setProcessed(source.getProcessed());
        target.setCreated(source.getCreated());
        target.setUpdated(source.getUpdated());
        target.setSkipped(source.getSkipped());
        target.setFailed(source.getFailed());
        return target;
    }
}