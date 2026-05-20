package com.aetherops.common.exception;

public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException() {
        super("Invalid or revoked API key");
    }
}
