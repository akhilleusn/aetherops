package com.aetherops.common.exception;

public class InvalidTotpCodeException extends RuntimeException {
    public InvalidTotpCodeException() {
        super("Invalid or expired authentication code.");
    }
}
