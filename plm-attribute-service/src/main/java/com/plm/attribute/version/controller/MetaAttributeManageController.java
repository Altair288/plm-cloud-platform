package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaAttributeManageService;
import com.plm.common.api.dto.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.MetaAttributeUpsertRequestDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/meta/attribute-defs")
public class MetaAttributeManageController {

    private final MetaAttributeManageService manageService;

    public MetaAttributeManageController(MetaAttributeManageService manageService) {
        this.manageService = manageService;
    }

    /** 创建属性：插入 def + 首个 version（versionNo=1, isLatest=true） */
    @PostMapping
    public ResponseEntity<MetaAttributeDefDetailDto> create(
            @RequestParam("categoryCode") String categoryCode,
            @RequestBody MetaAttributeUpsertRequestDto req,
            @RequestHeader(value = "X-User", required = false) String xUser,
            @RequestParam(value = "createdBy", required = false) String createdBy,
            @RequestParam(value = "includeValues", required = false, defaultValue = "true") boolean includeValues) {
        String operator = pickOperator(xUser, createdBy);
        MetaAttributeDefDetailDto dto = manageService.create(categoryCode, req, operator);
        if (!includeValues && dto != null) {
            // 当前 manageService 返回 detail(includeValues=true)。若前端不需要枚举值可再扩展 manageService 支持。
        }
        return ResponseEntity.ok(dto);
    }

    /** 更新属性：若 hash 有变化则新增 version（versionNo+1, isLatest=true），旧 latest=false */
    @PutMapping("/{attrKey}")
    public ResponseEntity<MetaAttributeDefDetailDto> update(
            @PathVariable("attrKey") String attrKey,
            @RequestParam("categoryCode") String categoryCode,
            @RequestBody MetaAttributeUpsertRequestDto req,
            @RequestHeader(value = "X-User", required = false) String xUser,
            @RequestParam(value = "createdBy", required = false) String createdBy,
            @RequestParam(value = "includeValues", required = false, defaultValue = "true") boolean includeValues) {
        String operator = pickOperator(xUser, createdBy);
        MetaAttributeDefDetailDto dto = manageService.update(categoryCode, attrKey, req, operator);
        if (!includeValues && dto != null) {
            // 同上：如需返回不带枚举值的 detail，可扩展 manageService。
        }
        return ResponseEntity.ok(dto);
    }

    @PatchMapping("/{attrKey}")
    public ResponseEntity<MetaAttributeDefDetailDto> patch(
            @PathVariable("attrKey") String attrKey,
            @RequestParam("categoryCode") String categoryCode,
            @RequestBody MetaAttributeUpsertRequestDto req,
            @RequestHeader(value = "X-User", required = false) String xUser,
            @RequestParam(value = "createdBy", required = false) String createdBy,
            @RequestParam(value = "includeValues", required = false, defaultValue = "true") boolean includeValues) {
        return update(attrKey, categoryCode, req, xUser, createdBy, includeValues);
    }

    /** 删除属性：软删（将 meta_attribute_def.status 置为 deleted） */
    @DeleteMapping("/{attrKey}")
    public ResponseEntity<Void> delete(
            @PathVariable("attrKey") String attrKey,
            @RequestParam("categoryCode") String categoryCode,
            @RequestHeader(value = "X-User", required = false) String xUser,
            @RequestParam(value = "createdBy", required = false) String createdBy) {
        String operator = pickOperator(xUser, createdBy);
        manageService.delete(categoryCode, attrKey, operator);
        return ResponseEntity.noContent().build();
    }

    private String pickOperator(String xUser, String createdBy) {
        if (xUser != null && !xUser.isBlank())
            return xUser.trim();
        if (createdBy != null && !createdBy.isBlank())
            return createdBy.trim();
        return "system";
    }
}
