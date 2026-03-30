package com.plm.common.api.dto.attribute;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateAttributeCodePreviewResponseDto {
    private String businessDomain;
    private String categoryCode;
    private String attributeRuleCode;
    private String generationMode;
    private Boolean allowManualOverride;
    private String suggestedCode;
    private List<String> examples;
    private List<String> warnings;
    private Map<String, String> resolvedContext;
    private String resolvedSequenceScope;
    private String resolvedPeriodKey;
    private Boolean previewStale;
    private String lovRuleCode;
    private Boolean allowLovValueManualOverride;
    private List<String> lovWarnings;
    private Map<String, String> lovResolvedContext;
    private String lovResolvedSequenceScope;
    private String lovResolvedPeriodKey;
    private List<LovValueCodePreviewItem> lovValuePreviews;

    @Data
    public static class LovValueCodePreviewItem {
        private Integer index;
        private String manualCode;
        private String name;
        private String label;
        private String suggestedCode;
    }
}