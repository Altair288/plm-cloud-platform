package com.plm.common.api.dto.attribute;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

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

    /** 属性字段（业务字段名） */
    private String attributeField;

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

    /** 数字型配置：最小值 */
    private BigDecimal minValue;
    /** 数字型配置：最大值 */
    private BigDecimal maxValue;
    /** 数字型配置：步长 */
    private BigDecimal step;
    /** 数字型配置：精度（小数位数） */
    private Integer precision;

    /** 布尔型配置：true 显示文本 */
    private String trueLabel;
    /** 布尔型配置：false 显示文本 */
    private String falseLabel;

    /** 枚举型配置项（code/name/label） */
    private List<LovValueUpsertItem> lovValues;

    @Data
    public static class LovValueUpsertItem {
        private String code;
        private String name;
        private String label;
    }
}
