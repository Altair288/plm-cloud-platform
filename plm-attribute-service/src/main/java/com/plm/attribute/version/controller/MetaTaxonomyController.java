package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCategoryGenericQueryService;
import com.plm.common.api.dto.MetaTaxonomyDto;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meta/taxonomies")
public class MetaTaxonomyController {

    private final MetaCategoryGenericQueryService queryService;

    public MetaTaxonomyController(MetaCategoryGenericQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{code}")
    public MetaTaxonomyDto getByCode(@PathVariable("code") String code) {
        return queryService.taxonomy(code);
    }
}
