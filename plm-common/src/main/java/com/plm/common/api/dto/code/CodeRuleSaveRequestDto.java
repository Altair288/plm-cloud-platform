package com.plm.common.api.dto.code;

import lombok.Data;

import java.util.Map;

@Data
public class CodeRuleSaveRequestDto {
    private String ruleCode;
    private String name;
    private String targetType;
    private String scopeType;
    private String scopeValue;
    private String pattern;
    private Boolean allowManualOverride;
    private String regexPattern;
    private Integer maxLength;
    private Map<String, Object> ruleJson;
}