package com.aetherops.common.exception;

import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.common.response.ErrorResponse;
import com.aetherops.common.exception.AccountLockedException;
import com.aetherops.common.exception.DuplicateEmailException;
import com.aetherops.common.exception.ForbiddenException;
import com.aetherops.common.exception.InvalidOAuthStateException;
import com.aetherops.common.exception.EmailNotVerifiedException;
import com.aetherops.common.exception.InvalidResetTokenException;
import com.aetherops.common.exception.InvalidVerificationTokenException;
import com.aetherops.common.exception.InvalidApiKeyException;
import com.aetherops.common.exception.InvalidCredentialsException;
import com.aetherops.common.exception.GoogleAuthNotConfiguredException;
import com.aetherops.common.exception.InvalidGoogleTokenException;
import com.aetherops.common.exception.InvalidInvitationException;
import com.aetherops.common.exception.InvalidTotpCodeException;
import com.aetherops.common.exception.MissingTenantIdException;
import com.aetherops.common.exception.SlugAlreadyTakenException;
import com.aetherops.common.exception.InvalidRefreshTokenException;
import com.aetherops.common.exception.RefreshTokenReuseException;
import com.aetherops.common.exception.RateLimitExceededException;
import com.aetherops.common.exception.TwoFaAlreadyEnabledException;
import com.aetherops.common.exception.TwoFaNotEnabledException;
import com.aetherops.common.exception.TwoFaSessionLockedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final AuditLogService auditLogService;

    @ExceptionHandler(UsageLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleUsageLimitExceeded(
        UsageLimitExceededException ex,
        HttpServletRequest request
    ) {
        // The outer @Transactional has already rolled back at this point.
        // record() opens a fresh transaction so the BLOCKED entry is always persisted.
        // Wrapped in try-catch so an audit write failure never turns a 429 into a 500.
        try {
            auditLogService.record(
                ex.getTenantId(), AuditAction.USAGE_LIMIT_BLOCKED, AuditStatus.BLOCKED,
                "USAGE", null,
                "Monthly " + ex.getUsageType() + " limit of " + ex.getLimit()
                    + " reached (plan: " + ex.getPlan() + ")"
            );
        } catch (Exception auditEx) {
            log.error("Failed to write USAGE_LIMIT_BLOCKED audit entry for tenant={}",
                ex.getTenantId(), auditEx);
        }

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(IncidentValidationException.class)
    public ResponseEntity<ErrorResponse> handleIncidentValidation(
        IncidentValidationException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse.of(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MissingTenantIdException.class)
    public ResponseEntity<ErrorResponse> handleMissingTenantId(
        MissingTenantIdException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse.withDetails(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Validation failed",
                request.getRequestURI(),
                java.util.Map.of("tenantId", ex.getMessage())
            ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(
        RateLimitExceededException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotVerified(
        EmailNotVerifiedException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse.of(HttpStatus.FORBIDDEN, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationToken(
        InvalidVerificationTokenException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLocked(
        AccountLockedException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.LOCKED)
            .body(ErrorResponse.of(HttpStatus.LOCKED, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(TwoFaSessionLockedException.class)
    public ResponseEntity<ErrorResponse> handleTwoFaSessionLocked(
        TwoFaSessionLockedException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.LOCKED)
            .body(ErrorResponse.of(HttpStatus.LOCKED, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidInvitationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInvitation(
        InvalidInvitationException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResetToken(
        InvalidResetTokenException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidTotpCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTotpCode(
        InvalidTotpCodeException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(TwoFaAlreadyEnabledException.class)
    public ResponseEntity<ErrorResponse> handleTwoFaAlreadyEnabled(
        TwoFaAlreadyEnabledException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(TwoFaNotEnabledException.class)
    public ResponseEntity<ErrorResponse> handleTwoFaNotEnabled(
        TwoFaNotEnabledException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(
        DuplicateEmailException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(SlugAlreadyTakenException.class)
    public ResponseEntity<ErrorResponse> handleSlugAlreadyTaken(
        SlugAlreadyTakenException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse.of(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidGoogleTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidGoogleToken(
        InvalidGoogleTokenException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(GoogleAuthNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleGoogleAuthNotConfigured(
        GoogleAuthNotConfiguredException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(
        InvalidRefreshTokenException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(RefreshTokenReuseException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenReuse(
        RefreshTokenReuseException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
        InvalidCredentialsException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidApiKey(
        InvalidApiKeyException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidOAuthStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOAuthState(
        InvalidOAuthStateException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(
        ForbiddenException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse.of(HttpStatus.FORBIDDEN, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
        ResourceNotFoundException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
        AccessDeniedException ex,
        HttpServletRequest request
    ) {
        // @PreAuthorize throws this on a role/permission denial. Without a dedicated handler
        // here, the catch-all Exception.class handler below matches it first (Spring MVC's
        // @ExceptionHandler resolution runs inside DispatcherServlet, before Spring Security's
        // own ExceptionTranslationFilter — a servlet filter — ever gets a chance to see it),
        // turning every role denial into a 500 instead of a 403.
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse.of(HttpStatus.FORBIDDEN, "Access denied", request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
        NoResourceFoundException ex,
        HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(HttpStatus.NOT_FOUND, "Not found", request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse.withDetails(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Validation failed",
                request.getRequestURI(),
                fieldErrors
            ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred",
                request.getRequestURI()
            ));
    }
}
