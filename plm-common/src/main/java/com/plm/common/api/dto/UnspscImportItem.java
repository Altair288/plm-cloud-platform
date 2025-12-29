package com.plm.common.api.dto;

/**
 * 简单 UNSPSC 行模型：键、父键、编码、标题。
 */
public class UnspscImportItem {
    private String key;
    private String parentKey;
    private String code;
    private String title;

    public UnspscImportItem() {
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getParentKey() {
        return parentKey;
    }

    public void setParentKey(String parentKey) {
        this.parentKey = parentKey;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}