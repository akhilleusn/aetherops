package com.aetherops.sso;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigureSsoRequest {

    @NotBlank(message = "idpEntityId is required")
    private String idpEntityId;

    @NotBlank(message = "idpSsoUrl is required")
    private String idpSsoUrl;

    @NotBlank(message = "idpCertificate is required (PEM-encoded X.509)")
    private String idpCertificate;

    private boolean enabled;
}
