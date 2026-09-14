package com.aetherops.auth.model;

import java.util.List;

public record TwoFaSetupResponse(
    String qrCodeUrl,
    String secret,
    List<String> backupCodes
) {
    @Override
    public String toString() {
        return "TwoFaSetupResponse[qrCodeUrl=[REDACTED], secret=[REDACTED], backupCodes=[REDACTED]]";
    }
}
