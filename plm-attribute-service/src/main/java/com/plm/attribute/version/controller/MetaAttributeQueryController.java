package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaAttributeQueryService;
import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeDefListItemDto;
import com.plm.common.api.dto.attribute.MetaAttributeVersionSummaryDto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/meta/attribute-defs")
public class MetaAttributeQueryController {

    private static final String SUPPORTED_SORT_FIELDS = "createdAt,key,displayName,attributeField,dataType,latestVersionNo,categoryCode";

    private final MetaAttributeQueryService queryService;

    public MetaAttributeQueryController(MetaAttributeQueryService queryService) {
        this.queryService = queryService;
    }

    // 1. 列表查询（分页）
    @GetMapping
    public Page<MetaAttributeDefListItemDto> list(
            @RequestParam(value = "businessDomain", required = false) String businessDomain,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "dataType", required = false) String dataType,
            @RequestParam(value = "required", required = false) Boolean required,
            @RequestParam(value = "unique", required = false) Boolean unique,
            @RequestParam(value = "searchable", required = false) Boolean searchable,
            @RequestParam(value = "includeDeleted", required = false, defaultValue = "false") boolean includeDeleted,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sort", required = false) List<String> sort) {
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        return queryService.list(businessDomain, categoryCode, keyword, dataType, required, unique, searchable, includeDeleted, pageable);
    }

    private Sort resolveSort(List<String> sortParams) {
        if (sortParams == null || sortParams.isEmpty()) {
            return Sort.unsorted();
        }

        List<Sort.Order> orders = new ArrayList<>();
        for (int index = 0; index < sortParams.size(); index++) {
            String sortParam = sortParams.get(index);
            if (sortParam == null || sortParam.isBlank()) {
                continue;
            }

            String field;
            String directionToken = null;

            if (sortParam.contains(",")) {
                String[] parts = sortParam.split(",", 2);
                field = parts[0].trim();
                directionToken = parts.length == 2 ? parts[1].trim() : null;
            } else {
                field = sortParam.trim();
                if (index + 1 < sortParams.size()) {
                    String next = sortParams.get(index + 1);
                    if (next != null && Sort.Direction.fromOptionalString(next.trim().toUpperCase(Locale.ROOT)).isPresent()) {
                        directionToken = next.trim();
                        index++;
                    }
                }
            }

            String property = resolveSortProperty(field);
            Sort.Direction direction = Sort.Direction.ASC;
            if (directionToken != null && !directionToken.isBlank()) {
                try {
                    direction = Sort.Direction.fromString(directionToken.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("unsupported sort direction: " + directionToken);
                }
            }
            orders.add(new Sort.Order(direction, property));
        }

        if (orders.isEmpty()) {
            return Sort.unsorted();
        }

        boolean hasKeySort = orders.stream().anyMatch(order -> "attributeDef.key".equals(order.getProperty()));
        if (!hasKeySort) {
            orders.add(new Sort.Order(Sort.Direction.ASC, "attributeDef.key"));
        }
        return Sort.by(orders);
    }

    private String resolveSortProperty(String field) {
        return switch (field) {
            case "createdAt" -> "attributeDef.createdAt";
            case "key" -> "attributeDef.key";
            case "displayName" -> "displayName";
            case "attributeField" -> "attributeField";
            case "dataType" -> "dataType";
            case "latestVersionNo" -> "versionNo";
            case "categoryCode" -> "attributeDef.categoryDef.codeKey";
            default -> throw new IllegalArgumentException("unsupported sort field: " + field + ", allowed: " + SUPPORTED_SORT_FIELDS);
        };
    }

    // 2. 详情（含最新版本 + 所有历史版本摘要）
    @GetMapping("/{attrKey}")
    public MetaAttributeDefDetailDto detail(@PathVariable("attrKey") String attrKey,
            @RequestParam("businessDomain") String businessDomain,
            @RequestParam(value = "includeValues", required = false, defaultValue = "false") boolean includeValues) {
        MetaAttributeDefDetailDto dto = queryService.detail(businessDomain, attrKey, includeValues);
        if (dto == null)
            throw new IllegalArgumentException("属性不存在:" + attrKey);
        return dto;
    }

    // 3. 版本列表摘要
    @GetMapping("/{attrKey}/versions")
    public List<MetaAttributeVersionSummaryDto> versions(@PathVariable("attrKey") String attrKey,
            @RequestParam("businessDomain") String businessDomain) {
        return queryService.versions(businessDomain, attrKey);
    }
}
