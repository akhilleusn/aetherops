package com.aetherops.sso;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class TenantSsoConfigResponse {
    private UUID tenantId;
    private boolean enabled;
    private String idpEntityId;
    private String idpSsoUrl;
    /** SP-side metadata the tenant's IdP admin needs to configure: where assertions get POSTed back to. */
    private String acsUrl;
    private String spEntityId;
    private Instant updatedAt;
}
