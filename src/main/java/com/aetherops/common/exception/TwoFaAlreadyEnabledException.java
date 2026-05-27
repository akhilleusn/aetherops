package com.aetherops.common.exception;

public class TwoFaAlreadyEnabledException extends RuntimeException {
    public TwoFaAlreadyEnabledException() {
        super("Two-factor authentication is already enabled.");
    }
}
