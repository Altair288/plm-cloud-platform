package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaCodeRuleSetService;
import com.plm.common.api.dto.code.CodeRuleSetDetailDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSummaryDto;
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
@RequestMapping("/api/meta/code-rule-sets")
public class MetaCodeRuleSetController {

    private final MetaCodeRuleSetService codeRuleSetService;

    public MetaCodeRuleSetController(MetaCodeRuleSetService codeRuleSetService) {
        this.codeRuleSetService = codeRuleSetService;
    }

    @GetMapping
    public List<CodeRuleSetSummaryDto> list() {
        return codeRuleSetService.list();
    }

    @GetMapping("/{businessDomain}")
    public CodeRuleSetDetailDto detail(@PathVariable("businessDomain") String businessDomain) {
        return codeRuleSetService.detail(businessDomain);
    }

    @PostMapping
    public CodeRuleSetDetailDto create(@RequestBody CodeRuleSetSaveRequestDto request,
                                       @RequestParam(value = "operator", required = false) String operator) {
        return codeRuleSetService.create(request, operator);
    }

    @PutMapping("/{businessDomain}")
    public CodeRuleSetDetailDto update(@PathVariable("businessDomain") String businessDomain,
                                       @RequestBody CodeRuleSetSaveRequestDto request,
                                       @RequestParam(value = "operator", required = false) String operator) {
        return codeRuleSetService.update(businessDomain, request, operator);
    }

    @PostMapping("/{businessDomain}:publish")
    public CodeRuleSetDetailDto publish(@PathVariable("businessDomain") String businessDomain,
                                        @RequestParam(value = "operator", required = false) String operator) {
        return codeRuleSetService.publish(businessDomain, operator);
    }
}