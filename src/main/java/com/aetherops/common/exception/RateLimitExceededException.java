package com.aetherops.common.exception;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException() {
        super("Rate limit exceeded. Try again later.");
    }
}
