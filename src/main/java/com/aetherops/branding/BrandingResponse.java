package com.aetherops.branding;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class BrandingResponse {
    private UUID tenantId;
    private String companyName;
    private String logoUrl;
    private String primaryColor;
    private String secondaryColor;
    private Instant updatedAt;
}
