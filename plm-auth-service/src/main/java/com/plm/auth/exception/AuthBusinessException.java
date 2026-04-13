package com.plm.auth.exception;

import com.plm.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class AuthBusinessException extends BaseException {
    private final String code;
    private final HttpStatus status;

    public AuthBusinessException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}