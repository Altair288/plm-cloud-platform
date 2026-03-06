package com.plm.attribute.version.exception;

public class CategoryConflictException extends RuntimeException {
    private final String code;

    public CategoryConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
