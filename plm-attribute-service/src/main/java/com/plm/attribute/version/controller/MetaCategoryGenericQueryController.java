package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCategoryGenericQueryService;
import com.plm.common.api.dto.MetaCategoryChildrenBatchRequestDto;
import com.plm.common.api.dto.MetaCategoryNodeDto;
import com.plm.common.api.dto.MetaCategorySearchItemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/meta/categories")
public class MetaCategoryGenericQueryController {

    private final MetaCategoryGenericQueryService queryService;

    public MetaCategoryGenericQueryController(MetaCategoryGenericQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/nodes")
    public Page<MetaCategoryNodeDto> nodes(
            @RequestParam("taxonomy") String taxonomy,
            @RequestParam(value = "parentId", required = false) UUID parentId,
            @RequestParam(value = "level", required = false) Integer level,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false, defaultValue = "ACTIVE") String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return queryService.listNodes(taxonomy, parentId, level, keyword, status, pageable);
    }

    @GetMapping("/nodes/{id}/path")
    public List<MetaCategoryNodeDto> path(
            @PathVariable("id") UUID id,
            @RequestParam("taxonomy") String taxonomy) {
        return queryService.path(id, taxonomy);
    }

    @GetMapping("/search")
    public Page<MetaCategorySearchItemDto> search(
            @RequestParam("taxonomy") String taxonomy,
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "scopeNodeId", required = false) UUID scopeNodeId,
            @RequestParam(value = "maxDepth", required = false) Integer maxDepth,
            @RequestParam(value = "status", required = false, defaultValue = "ACTIVE") String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return queryService.search(taxonomy, keyword, scopeNodeId, maxDepth, status, pageable);
    }

    @PostMapping("/nodes:children-batch")
    public Map<UUID, List<MetaCategoryNodeDto>> childrenBatch(
            @RequestBody MetaCategoryChildrenBatchRequestDto request) {
        return queryService.childrenBatch(request);
    }
}
