package com.plm.common.api.dto;

import lombok.Data;

@Data
public class MetaCategoryBrowseNodeDto {
    /**
     * 对外标识：当前项目中约定使用 meta_category_def.code_key
     */
    private String key;

    /**
     * 业务编码：当前项目中与 key 相同（external_code 与 code_key 都来自导入表格第三列）
     */
    private String code;

    /**
     * 展示名称：优先使用最新版本 display_name
     */
    private String title;

    private Boolean hasChildren;
    private Short depth;
    private String fullPathName;
}
