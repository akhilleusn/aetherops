package com.aetherops.common.exception;

public class MissingTenantIdException extends RuntimeException {

    public MissingTenantIdException() {
        super("tenantId is required");
    }
}
