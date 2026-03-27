package com.plm.common.api.dto.code;

import lombok.Data;

@Data
public class CodeRuleSetSaveRequestDto {
    private String businessDomain;
    private String name;
    private String remark;
    private String categoryRuleCode;
    private String attributeRuleCode;
    private String lovRuleCode;
}