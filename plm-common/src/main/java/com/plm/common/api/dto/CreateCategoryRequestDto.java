package com.plm.common.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class CreateCategoryRequestDto {
    private String code;
    private String name;
    private String businessDomain;
    private UUID parentId;
    private String status;
    private String description;
    private Integer sort;
    private String taxonomy;
}
