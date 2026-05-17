package com.aetherops.common.response;

import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class ErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private Map<String, String> details;

    public static ErrorResponse of(HttpStatus httpStatus, String message, String path) {
        return ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(httpStatus.value())
            .error(httpStatus.getReasonPhrase())
            .message(message)
            .path(path)
            .build();
    }

    public static ErrorResponse withDetails(
        HttpStatus httpStatus,
        String error,
        String path,
        Map<String, String> details
    ) {
        return ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(httpStatus.value())
            .error(error)
            .message(error)
            .path(path)
            .details(details)
            .build();
    }
}