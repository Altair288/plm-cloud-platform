package com.plm.common.api.dto.code;

import lombok.Data;

import java.util.Map;

@Data
public class CodeRulePreviewRequestDto {
    private Map<String, String> context;
    private String manualCode;
    private Integer count;
}