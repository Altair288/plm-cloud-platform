package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCategoryImportService;
import com.plm.attribute.version.service.MetaCategoryHierarchyService;
import com.plm.common.api.dto.MetaCategoryImportSummaryDto;
import com.plm.common.api.dto.UnspscImportItem;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/meta/categories")
public class MetaCategoryController {

    private final MetaCategoryImportService importService;
    private final MetaCategoryHierarchyService hierarchyService;

    public MetaCategoryController(MetaCategoryImportService importService,
                                  MetaCategoryHierarchyService hierarchyService) {
        this.importService = importService;
        this.hierarchyService = hierarchyService;
    }

    @PostMapping(value = "/import", consumes = {"multipart/form-data"})
    public ResponseEntity<MetaCategoryImportSummaryDto> importExcel(@RequestParam("file") MultipartFile file,
                                                                    @RequestParam(value = "createdBy", required = false) String createdBy) throws Exception {
        MetaCategoryImportSummaryDto summary = importService.importExcel(file, createdBy == null ? "system" : createdBy);
        return ResponseEntity.ok(summary);
    }

    // 导入 UNSPSC CSV (key,parentKey,code,title)
    @PostMapping(value = "/import-unspsc-csv", consumes = {"multipart/form-data"})
    public ResponseEntity<MetaCategoryImportSummaryDto> importUnspscCsv(@RequestParam("file") MultipartFile file,
                                                                        @RequestParam(value = "createdBy", required = false) String createdBy) {
        MetaCategoryImportSummaryDto summary = importService.importUnspscCsv(file, createdBy == null ? "system" : createdBy);
        return ResponseEntity.ok(summary);
    }

    // 导入 UNSPSC 简化结构（JSON 数组）
    @PostMapping(value = "/import-unspsc", consumes = "application/json")
    public ResponseEntity<MetaCategoryImportSummaryDto> importUnspsc(@RequestBody List<UnspscImportItem> items,
                                                                     @RequestParam(value = "createdBy", required = false) String createdBy) {
        MetaCategoryImportSummaryDto summary = importService.importUnspsc(items, createdBy == null ? "system" : createdBy);
        return ResponseEntity.ok(summary);
    }

    // 重建闭包表
    @PostMapping("/rebuild-closure")
    public ResponseEntity<Map<String,Object>> rebuildClosure() {
        Map<String,Object> result = hierarchyService.rebuildClosure();
        return ResponseEntity.ok(result);
    }

    // 查询后代分类
    @GetMapping("/{id}/descendants")
    public ResponseEntity<List<com.plm.common.api.dto.MetaCategoryDefDto>> descendants(@PathVariable("id") java.util.UUID id) {
        var defs = hierarchyService.findDescendants(id);
        var dtoList = defs.stream().map(com.plm.common.api.mapper.MetaCategoryMapper::toDefDto).toList();
        return ResponseEntity.ok(dtoList);
    }
}
