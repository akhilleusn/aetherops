package com.aetherops.common.exception;

public class GoogleAuthNotConfiguredException extends RuntimeException {
    public GoogleAuthNotConfiguredException() {
        super("Google authentication is not configured.");
    }
}
