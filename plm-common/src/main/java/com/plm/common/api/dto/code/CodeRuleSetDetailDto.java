package com.plm.common.api.dto.code;

import lombok.Data;

import java.util.Map;

@Data
public class CodeRuleSetDetailDto {
    private String businessDomain;
    private String name;
    private String status;
    private Boolean active;
    private String remark;
    private String categoryRuleCode;
    private String attributeRuleCode;
    private String lovRuleCode;
    private Map<String, CodeRuleDetailDto> rules;
}