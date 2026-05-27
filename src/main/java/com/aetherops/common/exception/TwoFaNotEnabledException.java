package com.aetherops.common.exception;

public class TwoFaNotEnabledException extends RuntimeException {
    public TwoFaNotEnabledException() {
        super("Two-factor authentication is not enabled.");
    }
}
