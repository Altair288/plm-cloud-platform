package com.plm.attribute.version.service.workbook.export;

import com.plm.common.api.dto.exports.workbook.WorkbookExportColumnRequestDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportJobResultDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartRequestDto;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkbookExportWorkbookBuilder {

    private static final String CONTENT_TYPE_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public WorkbookExportSupport.ExportArtifact build(WorkbookExportStartRequestDto request,
                                                      WorkbookExportSupport.ExportDataBundle dataBundle,
                                                      WorkbookExportSchemaService schemaService) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            WorkbookExportJobResultDto result = new WorkbookExportJobResultDto();
            result.setResolvedRequest(request);
            result.setWarnings(List.of());
            result.setSummary(new WorkbookExportJobResultDto.SummaryDto());

            Set<String> usedSheetNames = new HashSet<>();
            for (WorkbookExportStartRequestDto.ModuleRequestDto module : request.getModules()) {
                String moduleKey = schemaService.normalizeModuleKey(module.getModuleKey());
                List<WorkbookExportColumnRequestDto> columns = schemaService.normalizeColumns(moduleKey, module.getColumns());
                if (columns.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> rows = rowsForModule(moduleKey, dataBundle);
                String sheetName = uniqueSheetName(resolveSheetName(module, moduleKey, schemaService), usedSheetNames);
                writeSheet(workbook.createSheet(sheetName), columns, rows);
                applySummary(result.getSummary(), moduleKey, sheetName, rows.size());
            }

            workbook.write(output);
            byte[] bytes = output.toByteArray();

            WorkbookExportJobResultDto.FileDto file = new WorkbookExportJobResultDto.FileDto();
            file.setFileName(resolveFileName(request));
            file.setContentType(CONTENT_TYPE_XLSX);
            file.setSize((long) bytes.length);
            file.setChecksum(sha256(bytes));
            file.setExpiresAt(OffsetDateTime.now().plusHours(1));
            result.setFile(file);
            result.setStatus(WorkbookExportSupport.STATUS_COMPLETED);
            result.setCompletedAt(OffsetDateTime.now());

            return new WorkbookExportSupport.ExportArtifact(bytes, result);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to build workbook export file", ex);
        }
    }

    private List<Map<String, Object>> rowsForModule(String moduleKey,
                                                    WorkbookExportSupport.ExportDataBundle bundle) {
        return switch (moduleKey) {
            case WorkbookExportSupport.MODULE_CATEGORY -> bundle.categories();
            case WorkbookExportSupport.MODULE_ATTRIBUTE -> bundle.attributes();
            case WorkbookExportSupport.MODULE_ENUM_OPTION -> bundle.enumOptions();
            default -> throw new IllegalArgumentException("unsupported workbook export moduleKey: " + moduleKey);
        };
    }

    private String resolveSheetName(WorkbookExportStartRequestDto.ModuleRequestDto module,
                                    String moduleKey,
                                    WorkbookExportSchemaService schemaService) {
        String sheetName = module.getSheetName();
        if (sheetName == null || sheetName.isBlank()) {
            return schemaService.defaultSheetName(moduleKey);
        }
        return sheetName.trim();
    }

    private String uniqueSheetName(String requestedName, Set<String> usedSheetNames) {
        String baseName = requestedName == null || requestedName.isBlank() ? "Sheet" : requestedName.trim();
        String candidate = truncateSheetName(baseName);
        int index = 2;
        while (!usedSheetNames.add(candidate)) {
            candidate = truncateSheetName(baseName + "_" + index++);
        }
        return candidate;
    }

    private String truncateSheetName(String name) {
        return name.length() <= 31 ? name : name.substring(0, 31);
    }

    private void writeSheet(Sheet sheet,
                            List<WorkbookExportColumnRequestDto> columns,
                            List<Map<String, Object>> rows) {
        Row headerRow = sheet.createRow(0);
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            WorkbookExportColumnRequestDto column = columns.get(columnIndex);
            headerRow.createCell(columnIndex).setCellValue(column.getHeaderText());
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            Row excelRow = sheet.createRow(rowIndex + 1);
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                WorkbookExportColumnRequestDto column = columns.get(columnIndex);
                writeCell(excelRow.createCell(columnIndex), row.get(column.getFieldKey()));
            }
        }
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        if (value instanceof OffsetDateTime dateTime) {
            cell.setCellValue(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime));
            return;
        }
        cell.setCellValue(Objects.toString(value, ""));
    }

    private void applySummary(WorkbookExportJobResultDto.SummaryDto summary,
                              String moduleKey,
                              String sheetName,
                              int rows) {
        WorkbookExportJobResultDto.ModuleSummaryDto moduleSummary = new WorkbookExportJobResultDto.ModuleSummaryDto();
        moduleSummary.setSheetName(sheetName);
        moduleSummary.setTotalRows(rows);
        moduleSummary.setExportedRows(rows);
        switch (moduleKey) {
            case WorkbookExportSupport.MODULE_CATEGORY -> summary.setCategories(moduleSummary);
            case WorkbookExportSupport.MODULE_ATTRIBUTE -> summary.setAttributes(moduleSummary);
            case WorkbookExportSupport.MODULE_ENUM_OPTION -> summary.setEnumOptions(moduleSummary);
            default -> throw new IllegalArgumentException("unsupported workbook export moduleKey: " + moduleKey);
        }
    }

    private String resolveFileName(WorkbookExportStartRequestDto request) {
        String custom = request.getOutput() == null ? null : request.getOutput().getFileName();
        if (custom != null && !custom.isBlank()) {
            String trimmed = custom.trim();
            return trimmed.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? trimmed : trimmed + ".xlsx";
        }
        String businessDomain = request.getBusinessDomain() == null ? "metadata" : request.getBusinessDomain().trim().toLowerCase(Locale.ROOT);
        return businessDomain + "-workbook-export-" + java.time.LocalDate.now() + ".xlsx";
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to calculate workbook export checksum", ex);
        }
    }
}