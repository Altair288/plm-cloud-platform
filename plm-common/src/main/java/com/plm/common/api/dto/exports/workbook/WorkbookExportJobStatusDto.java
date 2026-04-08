package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class WorkbookExportJobStatusDto {
    private String jobId;
    private String businessDomain;
    private String status;
    private String currentStage;
    private String fileName;
    private Integer overallPercent;
    private Integer stagePercent;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;
    private ProgressDto progress;
    private String latestLogCursor;
    private List<WorkbookExportLogEventDto> latestLogs;
    private List<String> warnings;

    @Data
    public static class ProgressDto {
        private ModuleProgressDto categories;
        private ModuleProgressDto attributes;
        private ModuleProgressDto enumOptions;
    }

    @Data
    public static class ModuleProgressDto {
        private Integer total;
        private Integer processed;
        private Integer exported;
        private Integer failed;
    }
}