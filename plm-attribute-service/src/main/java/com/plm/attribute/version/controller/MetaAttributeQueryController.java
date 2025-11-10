package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaAttributeQueryService;
import com.plm.common.api.dto.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.MetaAttributeDefListItemDto;
import com.plm.common.api.dto.MetaAttributeVersionSummaryDto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/meta/attribute-defs")
public class MetaAttributeQueryController {

    private final MetaAttributeQueryService queryService;

    public MetaAttributeQueryController(MetaAttributeQueryService queryService) {
        this.queryService = queryService;
    }

    // 1. 列表查询（分页）
    @GetMapping
    public Page<MetaAttributeDefListItemDto> list(
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return queryService.list(categoryCode, keyword, pageable);
    }

    // 2. 详情（含最新版本 + 所有历史版本摘要）
    @GetMapping("/{attrKey}")
    public MetaAttributeDefDetailDto detail(@PathVariable("attrKey") String attrKey,
                                            @RequestParam(value = "includeValues", required = false, defaultValue = "false") boolean includeValues) {
        MetaAttributeDefDetailDto dto = queryService.detail(attrKey, includeValues);
        if (dto == null)
            throw new IllegalArgumentException("属性不存在:" + attrKey);
        return dto;
    }

    // 3. 版本列表摘要
    @GetMapping("/{attrKey}/versions")
    public List<MetaAttributeVersionSummaryDto> versions(@PathVariable("attrKey") String attrKey) {
        return queryService.versions(attrKey);
    }
}
