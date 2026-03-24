package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCodeRuleService;
import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
import com.plm.common.api.dto.code.CodeRulePreviewResponseDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/meta/code-rules")
public class MetaCodeRuleController {

    private final MetaCodeRuleService codeRuleService;

    public MetaCodeRuleController(MetaCodeRuleService codeRuleService) {
        this.codeRuleService = codeRuleService;
    }

    @GetMapping
    public List<CodeRuleDetailDto> list(
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "status", required = false) String status) {
        return codeRuleService.list(targetType, status);
    }

    @GetMapping("/{ruleCode}")
    public CodeRuleDetailDto detail(@PathVariable("ruleCode") String ruleCode) {
        return codeRuleService.detail(ruleCode);
    }

    @PostMapping
    public CodeRuleDetailDto create(
            @RequestBody CodeRuleSaveRequestDto request,
            @RequestParam(value = "operator", required = false) String operator) {
        return codeRuleService.create(request, operator);
    }

    @PutMapping("/{ruleCode}")
    public CodeRuleDetailDto update(
            @PathVariable("ruleCode") String ruleCode,
            @RequestBody CodeRuleSaveRequestDto request,
            @RequestParam(value = "operator", required = false) String operator) {
        return codeRuleService.update(ruleCode, request, operator);
    }

    @PostMapping("/{ruleCode}:publish")
    public CodeRuleDetailDto publish(
            @PathVariable("ruleCode") String ruleCode,
            @RequestParam(value = "operator", required = false) String operator) {
        return codeRuleService.publish(ruleCode, operator);
    }

    @PostMapping("/{ruleCode}:preview")
    public CodeRulePreviewResponseDto preview(
            @PathVariable("ruleCode") String ruleCode,
            @RequestBody(required = false) CodeRulePreviewRequestDto request) {
        return codeRuleService.preview(ruleCode, request);
    }
}