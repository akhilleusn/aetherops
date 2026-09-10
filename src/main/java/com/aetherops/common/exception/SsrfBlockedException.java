package com.aetherops.common.exception;

/** Thrown by {@code com.aetherops.common.security.SsrfGuard} when a URL fails its safety checks. */
public class SsrfBlockedException extends RuntimeException {
    public SsrfBlockedException(String message) {
        super(message);
    }
}
