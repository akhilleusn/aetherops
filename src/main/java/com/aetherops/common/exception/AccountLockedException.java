package com.aetherops.common.exception;

public class AccountLockedException extends RuntimeException {
    public AccountLockedException(long minutesRemaining) {
        super("Account locked. Try again in " + minutesRemaining + " minute(s).");
    }
}
