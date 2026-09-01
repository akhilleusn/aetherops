package com.aetherops.statuspage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostStatusPageUpdateRequest {

    @NotNull(message = "status is required")
    private StatusPageUpdateStatus status;

    @NotBlank(message = "message is required")
    private String message;
}
