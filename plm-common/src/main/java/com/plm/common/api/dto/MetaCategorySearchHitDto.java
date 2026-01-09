package com.plm.common.api.dto;

import lombok.Data;

@Data
public class MetaCategorySearchHitDto {
    private String key;
    private String code;
    private String title;
    private Short depth;
    private String fullPathName;
}
