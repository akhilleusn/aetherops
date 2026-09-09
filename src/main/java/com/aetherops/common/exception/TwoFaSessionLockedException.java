package com.aetherops.common.exception;

/**
 * Thrown when a single 2FA session (identified by its partial token) has hit the failed-attempt
 * limit. Unlike AccountLockedException, this does not lock the account — the fix is simply to
 * log in again, which mints a fresh partial token and thus a fresh attempt counter.
 */
public class TwoFaSessionLockedException extends RuntimeException {
    public TwoFaSessionLockedException() {
        super("Too many failed authentication code attempts. Please log in again.");
    }
}
