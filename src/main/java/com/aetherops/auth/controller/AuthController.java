package com.aetherops.auth.controller;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.ForgotPasswordRequest;
import com.aetherops.auth.model.GoogleAuthRequest;
import com.aetherops.auth.model.LoginRequest;
import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.model.ResetPasswordRequest;
import com.aetherops.auth.service.AuthService;
import com.aetherops.auth.service.GoogleAuthService;
import com.aetherops.auth.service.GoogleTokenVerifier;
import com.aetherops.auth.service.PasswordResetService;
import com.aetherops.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Register and login to receive a JWT Bearer token")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final GoogleAuthService googleAuthService;
    private final GoogleTokenVerifier googleTokenVerifier;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user account")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login and receive a JWT token")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset email")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        return ApiResponse.success(null);
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using a valid reset token")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ApiResponse.success(null);
    }

    @PostMapping("/google")
    @Operation(summary = "Login or register via Google ID token")
    public ApiResponse<AuthResponse> googleLogin(@Valid @RequestBody GoogleAuthRequest request) {
        return ApiResponse.success(googleAuthService.authenticate(request));
    }

    @GetMapping("/google/client-id")
    @Operation(summary = "Return Google client ID for frontend initialisation")
    public ApiResponse<Map<String, String>> googleClientId() {
        return ApiResponse.success(Map.of("clientId",
            googleTokenVerifier.isConfigured() ? googleTokenVerifier.getClientId() : ""));
    }
}
