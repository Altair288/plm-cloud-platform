package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCategoryBrowseService;
import com.plm.common.api.dto.MetaCategoryBrowseNodeDto;
import com.plm.common.api.dto.MetaCategoryClassGroupDto;
import com.plm.common.api.dto.MetaCategorySearchHitDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/meta/categories/unspsc")
public class MetaCategoryBrowseController {

    private final MetaCategoryBrowseService browseService;

    public MetaCategoryBrowseController(MetaCategoryBrowseService browseService) {
        this.browseService = browseService;
    }

    /** Tabs：UNSPSC Segments（根节点） */
    @GetMapping("/segments")
    public ResponseEntity<List<MetaCategoryBrowseNodeDto>> segments() {
        return ResponseEntity.ok(browseService.listUnspscSegments());
    }

    /** 左侧：某个 segment 下 families（直接子节点） */
    @GetMapping("/segments/{segmentCodeKey}/families")
    public ResponseEntity<List<MetaCategoryBrowseNodeDto>> families(
            @PathVariable("segmentCodeKey") String segmentCodeKey) {
        return ResponseEntity.ok(browseService.listChildrenByCodeKey(segmentCodeKey));
    }

    /** 右侧：Family -> (Class + Commodities) */
    @GetMapping("/families/{familyCodeKey}/classes-with-commodities")
    public ResponseEntity<List<MetaCategoryClassGroupDto>> classesWithCommodities(
            @PathVariable("familyCodeKey") String familyCodeKey) {
        return ResponseEntity.ok(browseService.listClassesWithCommodities(familyCodeKey));
    }

    /** 搜索：支持全局或限定在某个节点子树内 */
    @GetMapping("/search")
    public ResponseEntity<List<MetaCategorySearchHitDto>> search(
            @RequestParam("q") String q,
            @RequestParam(value = "scopeCodeKey", required = false) String scopeCodeKey,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return ResponseEntity.ok(browseService.searchUnspsc(q, scopeCodeKey, limit));
    }
}
