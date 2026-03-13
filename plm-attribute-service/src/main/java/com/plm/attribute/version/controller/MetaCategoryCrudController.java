package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCategoryCrudService;
import com.plm.common.api.dto.CreateCategoryRequestDto;
import com.plm.common.api.dto.MetaCategoryBatchDeleteRequestDto;
import com.plm.common.api.dto.MetaCategoryBatchDeleteResponseDto;
import com.plm.common.api.dto.MetaCategoryDetailDto;
import com.plm.common.api.dto.MetaCategoryVersionCompareDto;
import com.plm.common.api.dto.UpdateCategoryRequestDto;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/meta/categories")
public class MetaCategoryCrudController {

    private final MetaCategoryCrudService crudService;

    public MetaCategoryCrudController(MetaCategoryCrudService crudService) {
        this.crudService = crudService;
    }

    @GetMapping("/{id}")
    public MetaCategoryDetailDto detail(@PathVariable("id") UUID id) {
        return crudService.detail(id);
    }

    @GetMapping("/{id}/versions/compare")
    public MetaCategoryVersionCompareDto compareVersions(
            @PathVariable("id") UUID id,
            @RequestParam("baseVersionId") UUID baseVersionId,
            @RequestParam("targetVersionId") UUID targetVersionId) {
        return crudService.compareVersions(id, baseVersionId, targetVersionId);
    }

    @PostMapping
    public MetaCategoryDetailDto create(
            @RequestBody CreateCategoryRequestDto request,
            @RequestParam(value = "operator", required = false) String operator) {
        return crudService.create(request, operator);
    }

    @PutMapping("/{id}")
    public MetaCategoryDetailDto update(
            @PathVariable("id") UUID id,
            @RequestBody UpdateCategoryRequestDto request,
            @RequestParam(value = "operator", required = false) String operator) {
        return crudService.update(id, request, operator, false);
    }

    @PatchMapping("/{id}")
    public MetaCategoryDetailDto patch(
            @PathVariable("id") UUID id,
            @RequestBody UpdateCategoryRequestDto request,
            @RequestParam(value = "operator", required = false) String operator) {
        return crudService.update(id, request, operator, true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable("id") UUID id,
            @RequestParam(value = "cascade", defaultValue = "false") boolean cascade,
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm,
            @RequestParam(value = "operator", required = false) String operator) {
        int deletedCount = crudService.delete(id, cascade, confirm, operator);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("cascade", cascade);
        body.put("deletedCount", deletedCount);
        return body;
    }

    @PostMapping(":batch-delete")
    public MetaCategoryBatchDeleteResponseDto batchDelete(
            @RequestBody MetaCategoryBatchDeleteRequestDto request) {
        return crudService.batchDelete(request);
    }
}
