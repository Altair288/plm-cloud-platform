package com.plm.common.api.dto;

import lombok.Data;

/**
 * 用于前端属性创建/编辑的请求结构。
 *
 * 约定：code 直接使用 meta_attribute_def.key（即该字段就是属性编码）。
 */
@Data
public class MetaAttributeUpsertRequestDto {
    /** 属性编码（对应 meta_attribute_def.key） */
    private String key;

    /** 名称（展示名） */
    private String displayName;

    /** 描述 */
    private String description;

    /** 数据类型（string/number/bool/enum/...） */
    private String dataType;

    /** 单位 */
    private String unit;

    /** 默认值（当前先按字符串处理；如需复杂类型可在后续升级为 JSON） */
    private String defaultValue;

    private Boolean required;
    private Boolean unique;
    private Boolean hidden;
    private Boolean readOnly;
    private Boolean searchable;

    /** 枚举型属性时可填（绑定的 lovKey） */
    private String lovKey;
}
