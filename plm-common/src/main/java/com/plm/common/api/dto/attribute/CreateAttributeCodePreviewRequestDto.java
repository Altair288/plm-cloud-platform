package com.plm.common.api.dto.attribute;

import lombok.Data;

import java.util.List;

@Data
public class CreateAttributeCodePreviewRequestDto {
    private String manualKey;
    private String dataType;
    private Integer count;
    private List<LovValuePreviewItem> lovValues;

    @Data
    public static class LovValuePreviewItem {
        private String code;
        private String name;
        private String label;
    }
}