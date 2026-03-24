package com.plm.common.api.dto.code;

import lombok.Data;

import java.util.List;

@Data
public class CodeRulePreviewResponseDto {
    private String ruleCode;
    private Integer ruleVersion;
    private String pattern;
    private List<String> examples;
    private List<String> warnings;
}