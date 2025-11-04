package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaAttributeImportService;
import com.plm.common.api.dto.AttributeImportSummaryDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/meta/attributes")
public class MetaAttributeImportController {

    private final MetaAttributeImportService importService;

    public MetaAttributeImportController(MetaAttributeImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttributeImportSummaryDto importAttributes(@RequestPart("file") MultipartFile file,
                                                      @RequestParam(value = "createdBy", required = false) String createdBy) throws Exception {
        if (createdBy == null || createdBy.isBlank()) createdBy = "system";
        return importService.importExcel(file, createdBy);
    }
}
