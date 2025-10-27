package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCategoryImportService;
import com.plm.common.api.dto.MetaCategoryImportSummaryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/meta/categories")
public class MetaCategoryController {

    private final MetaCategoryImportService importService;

    public MetaCategoryController(MetaCategoryImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/import", consumes = {"multipart/form-data"})
    public ResponseEntity<MetaCategoryImportSummaryDto> importExcel(@RequestParam("file") MultipartFile file,
                                                                    @RequestParam(value = "createdBy", required = false) String createdBy) throws Exception {
        MetaCategoryImportSummaryDto summary = importService.importExcel(file, createdBy == null ? "system" : createdBy);
        return ResponseEntity.ok(summary);
    }
}
