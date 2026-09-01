package com.aetherops.common.exception;

/** Thrown when a required incident field (title, serviceId) is still missing after template defaults are applied. */
public class IncidentValidationException extends RuntimeException {

    public IncidentValidationException(String message) {
        super(message);
    }
}
