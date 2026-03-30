package com.plm.common.api.dto.category;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateCategoryCodePreviewResponseDto {
    private String businessDomain;
    private String ruleCode;
    private String generationMode;
    private Boolean allowManualOverride;
    private String suggestedCode;
    private List<String> examples;
    private List<String> warnings;
    private Map<String, String> resolvedContext;
    private String resolvedSequenceScope;
    private String resolvedPeriodKey;
    private Boolean previewStale;
}