package com.aetherops.branding;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrandingRequest {

    @Size(max = 200)
    private String companyName;

    @Size(max = 500)
    private String logoUrl;

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "primaryColor must be a hex color like #1a2b3c")
    private String primaryColor;

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "secondaryColor must be a hex color like #1a2b3c")
    private String secondaryColor;
}
