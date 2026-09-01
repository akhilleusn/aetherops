package com.aetherops.cost;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddCostEntryRequest {

    @NotBlank(message = "description is required")
    private String description;

    @Positive(message = "amountCents must be positive")
    private long amountCents;

    private String currency;

    private String recordedBy;
}
