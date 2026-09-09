package com.aetherops.common.exception;

public class SlugAlreadyTakenException extends RuntimeException {
    public SlugAlreadyTakenException(String slug) {
        super("Slug '" + slug + "' is already taken");
    }
}
