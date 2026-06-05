package com.plm.infrastructure.storage;

public class StorageGatewayException extends RuntimeException {
    public StorageGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageGatewayException(String message) {
        super(message);
    }
}