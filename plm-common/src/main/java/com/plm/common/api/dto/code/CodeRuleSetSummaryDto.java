package com.plm.common.api.dto.code;

import lombok.Data;

@Data
public class CodeRuleSetSummaryDto {
    private String businessDomain;
    private String name;
    private String status;
    private Boolean active;
    private String remark;
    private String categoryRuleCode;
    private String attributeRuleCode;
    private String lovRuleCode;
}