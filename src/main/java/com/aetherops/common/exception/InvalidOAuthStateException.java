package com.aetherops.common.exception;

/** An OAuth "state" parameter that failed signature verification, expired, or doesn't match the caller/target of the request it's attached to. */
public class InvalidOAuthStateException extends RuntimeException {

    public InvalidOAuthStateException(String message) {
        super(message);
    }
}
