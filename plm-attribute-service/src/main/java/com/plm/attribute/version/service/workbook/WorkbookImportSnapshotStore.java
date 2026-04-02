package com.plm.attribute.version.service.workbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunOptionsDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.version.domain.MetaWorkbookImportSnapshot;
import com.plm.infrastructure.version.repository.MetaWorkbookImportSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class WorkbookImportSnapshotStore {

    private static final TypeReference<List<WorkbookImportSupport.ParsedCategoryRow>> CATEGORY_ROWS = new TypeReference<>() {
    };
    private static final TypeReference<List<WorkbookImportSupport.ParsedAttributeRow>> ATTRIBUTE_ROWS = new TypeReference<>() {
    };
    private static final TypeReference<List<WorkbookImportSupport.ParsedEnumOptionRow>> ENUM_ROWS = new TypeReference<>() {
    };

    private final MetaWorkbookImportSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public WorkbookImportSnapshotStore(MetaWorkbookImportSnapshotRepository snapshotRepository,
                                       ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    public WorkbookImportSupport.ImportSessionState save(WorkbookImportSupport.ImportSessionState session) {
        MetaWorkbookImportSnapshot snapshot = snapshotRepository.findByImportSessionId(session.importSessionId())
                .orElseGet(MetaWorkbookImportSnapshot::new);
        snapshot.setImportSessionId(session.importSessionId());
        snapshot.setOperatorName(session.operator());
        snapshot.setOptionsJson(writeJson(session.options()));
        snapshot.setResponseJson(writeJson(session.response()));
        snapshot.setCategoriesJson(writeJson(session.categories()));
        snapshot.setAttributesJson(writeJson(session.attributes()));
        snapshot.setEnumOptionsJson(writeJson(session.enumOptions()));
        snapshot.setExistingDataJson(writeJson(session.existingData()));
        snapshot.setExecutionPlanJson(writeJson(session.executionPlan()));
        snapshot.setStagedExecutionPlanJson(writeJson(session.stagedExecutionPlan()));
        snapshot.setCreatedAt(session.createdAt());
        snapshot.setExpiresAt(session.expiresAt());
        MetaWorkbookImportSnapshot saved = snapshotRepository.save(snapshot);
        return toSession(saved);
    }

    public void attachDryRunJobId(String importSessionId, String dryRunJobId) {
        if (importSessionId == null || importSessionId.isBlank() || dryRunJobId == null || dryRunJobId.isBlank()) {
            return;
        }
        snapshotRepository.findByImportSessionId(importSessionId).ifPresent(snapshot -> {
            snapshot.setDryRunJobId(dryRunJobId);
            snapshotRepository.save(snapshot);
        });
    }

    public Optional<WorkbookImportSupport.ImportSessionState> loadActiveSession(String importSessionId, OffsetDateTime now) {
        return snapshotRepository.findByImportSessionIdAndExpiresAtAfter(importSessionId, now).map(this::toSession);
    }

    public Optional<WorkbookImportSupport.ImportSessionState> loadActiveSessionByDryRunJobId(String dryRunJobId, OffsetDateTime now) {
        return snapshotRepository.findByDryRunJobIdAndExpiresAtAfter(dryRunJobId, now).map(this::toSession);
    }

    @Transactional
    public long deleteExpiredSnapshots(OffsetDateTime now) {
        return snapshotRepository.deleteByExpiresAtBefore(now);
    }

    private WorkbookImportSupport.ImportSessionState toSession(MetaWorkbookImportSnapshot snapshot) {
        try {
            return new WorkbookImportSupport.ImportSessionState(
                    snapshot.getImportSessionId(),
                    snapshot.getOperatorName(),
                    readJson(snapshot.getOptionsJson(), WorkbookImportDryRunOptionsDto.class),
                    readJson(snapshot.getResponseJson(), WorkbookImportDryRunResponseDto.class),
                    readJson(snapshot.getCategoriesJson(), CATEGORY_ROWS),
                    readJson(snapshot.getAttributesJson(), ATTRIBUTE_ROWS),
                    readJson(snapshot.getEnumOptionsJson(), ENUM_ROWS),
                    readJson(snapshot.getExistingDataJson(), WorkbookImportSupport.ExistingDataSnapshot.class),
                    readJson(snapshot.getExecutionPlanJson(), WorkbookImportSupport.ExecutionPlanSnapshot.class),
                    readJson(snapshot.getStagedExecutionPlanJson(), WorkbookImportSupport.ExecutionPlanSnapshot.class),
                    snapshot.getCreatedAt(),
                    snapshot.getExpiresAt());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("failed to deserialize workbook import snapshot: importSessionId="
                    + snapshot.getImportSessionId(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize workbook import snapshot", ex);
        }
    }

    private <T> T readJson(String json, Class<T> targetType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, targetType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize workbook import snapshot", ex);
        }
    }

    private <T> T readJson(String json, TypeReference<T> targetType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, targetType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize workbook import snapshot", ex);
        }
    }
}