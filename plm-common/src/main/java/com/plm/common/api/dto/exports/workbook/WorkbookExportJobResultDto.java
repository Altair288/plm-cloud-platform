package com.plm.common.api.dto.exports.workbook;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class WorkbookExportJobResultDto {
    private String jobId;
    private String status;
    private SummaryDto summary;
    private WorkbookExportStartRequestDto resolvedRequest;
    private FileDto file;
    private List<String> warnings;
    private OffsetDateTime completedAt;

    @Data
    public static class SummaryDto {
        private ModuleSummaryDto categories;
        private ModuleSummaryDto attributes;
        private ModuleSummaryDto enumOptions;
    }

    @Data
    public static class ModuleSummaryDto {
        private String sheetName;
        private Integer totalRows;
        private Integer exportedRows;
    }

    @Data
    public static class FileDto {
        private String fileName;
        private String contentType;
        private Long size;
        private String checksum;
        private OffsetDateTime expiresAt;
    }
}