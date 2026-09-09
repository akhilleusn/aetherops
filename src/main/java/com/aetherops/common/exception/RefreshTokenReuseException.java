package com.aetherops.common.exception;

/**
 * Thrown when an already-rotated-away refresh token is presented again — the standard signal
 * that a token has leaked. The entire token family is revoked before this is thrown, so
 * whoever holds any token from that family (legitimate user included) is forced to log in again.
 */
public class RefreshTokenReuseException extends RuntimeException {
    public RefreshTokenReuseException() {
        super("Refresh token reuse detected. Please log in again.");
    }
}
