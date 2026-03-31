package com.plm.common.api.dto.imports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class WorkbookImportJobStatusDto {
    private String jobId;
    private String importSessionId;
    private String status;
    private String currentStage;
    private Integer overallPercent;
    private Integer stagePercent;
    private OffsetDateTime startedAt;
    private OffsetDateTime updatedAt;
    private ProgressDto progress;
    private String latestLogCursor;
    private List<WorkbookImportLogEventDto> latestLogs;

    @Data
    public static class ProgressDto {
        private EntityProgressDto categories;
        private EntityProgressDto attributes;
        private EntityProgressDto enumOptions;
    }

    @Data
    public static class EntityProgressDto {
        private Integer total;
        private Integer processed;
        private Integer created;
        private Integer updated;
        private Integer skipped;
        private Integer failed;
    }
}