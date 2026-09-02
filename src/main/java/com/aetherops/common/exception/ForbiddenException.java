package com.aetherops.common.exception;

/** The caller is authenticated but not authorized for the specific resource requested. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
