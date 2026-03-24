package com.plm.common.api.dto.code;

import lombok.Data;

import java.util.Map;

@Data
public class CodeRuleDetailDto {
    private String ruleCode;
    private String name;
    private String targetType;
    private String scopeType;
    private String scopeValue;
    private String pattern;
    private String status;
    private Boolean active;
    private Boolean allowManualOverride;
    private String regexPattern;
    private Integer maxLength;
    private Integer latestVersionNo;
    private Map<String, Object> latestRuleJson;
}