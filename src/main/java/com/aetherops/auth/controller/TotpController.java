package com.aetherops.auth.controller;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.TwoFaDisableRequest;
import com.aetherops.auth.model.TwoFaSetupResponse;
import com.aetherops.auth.model.TwoFaVerifyRequest;
import com.aetherops.auth.model.TwoFaVerifySetupRequest;
import com.aetherops.auth.service.JwtService;
import com.aetherops.auth.service.TotpService;
import com.aetherops.common.exception.InvalidCredentialsException;
import com.aetherops.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/2fa")
@RequiredArgsConstructor
@Tag(name = "Two-Factor Auth", description = "TOTP-based two-factor authentication")
public class TotpController {

    private final TotpService totpService;
    private final JwtService jwtService;

    @PostMapping("/setup")
    @Operation(summary = "Generate TOTP secret and backup codes")
    public ApiResponse<TwoFaSetupResponse> setup(HttpServletRequest request) {
        return ApiResponse.success(totpService.setup(requireEmail(request)));
    }

    @PostMapping("/verify-setup")
    @Operation(summary = "Confirm TOTP setup with a valid code")
    public ApiResponse<Void> verifySetup(
        HttpServletRequest request,
        @Valid @RequestBody TwoFaVerifySetupRequest body
    ) {
        totpService.verifySetup(requireEmail(request), body.code());
        return ApiResponse.success(null);
    }

    @PostMapping("/verify")
    @Operation(summary = "Verify TOTP code and exchange partial token for full JWT")
    public ApiResponse<AuthResponse> verify(@Valid @RequestBody TwoFaVerifyRequest body) {
        return ApiResponse.success(totpService.verifyAndIssueToken(body.partialToken(), body.code()));
    }

    @PostMapping("/disable")
    @Operation(summary = "Disable 2FA with password confirmation")
    public ApiResponse<Void> disable(
        HttpServletRequest request,
        @Valid @RequestBody TwoFaDisableRequest body
    ) {
        totpService.disable(requireEmail(request), body.password());
        return ApiResponse.success(null);
    }

    private String requireEmail(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new InvalidCredentialsException();
        }
        String token = header.substring(7);
        if (!jwtService.isTokenValid(token) || jwtService.isPartialToken(token)) {
            throw new InvalidCredentialsException();
        }
        return jwtService.extractEmail(token);
    }
}
