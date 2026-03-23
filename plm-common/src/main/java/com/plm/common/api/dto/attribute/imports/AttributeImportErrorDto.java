package com.plm.common.api.dto.attribute.imports;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AttributeImportErrorDto {
    private int rowIndex;
    private String message;
}
