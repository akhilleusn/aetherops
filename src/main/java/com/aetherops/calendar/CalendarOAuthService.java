package com.aetherops.calendar;

import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * Plain-REST OAuth2 authorization-code exchange against Google's and Microsoft's
 * token endpoints — no google-api-client / MSAL SDK dependency. Client id/secret
 * default to blank; a real deployment must supply them (APP_CALENDAR_GOOGLE_* /
 * APP_CALENDAR_OUTLOOK_* env vars once someone wires the app.calendar.* properties
 * into application.yml) before the token exchange call itself will succeed — the
 * bean constructs and is unit-testable without them.
 */
@Service
@Slf4j
public class CalendarOAuthService {

    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String OUTLOOK_TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private static final String GOOGLE_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String OUTLOOK_AUTHORIZE_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/calendar.readonly";
    private static final String OUTLOOK_SCOPE = "offline_access Calendars.Read";

    private final UserCalendarConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final CalendarTokenEncryptor tokenEncryptor;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    private final String googleClientId;
    private final String googleClientSecret;
    private final String outlookClientId;
    private final String outlookClientSecret;

    public CalendarOAuthService(
        UserCalendarConnectionRepository connectionRepository,
        UserRepository userRepository,
        CalendarTokenEncryptor tokenEncryptor,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        @Value("${app.calendar.google-client-id:}") String googleClientId,
        @Value("${app.calendar.google-client-secret:}") String googleClientSecret,
        @Value("${app.calendar.outlook-client-id:}") String outlookClientId,
        @Value("${app.calendar.outlook-client-secret:}") String outlookClientSecret
    ) {
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        this.outlookClientId = outlookClientId;
        this.outlookClientSecret = outlookClientSecret;
    }

    /**
     * The URL to send the user's browser to. {@code state} must come from
     * {@link CalendarOAuthStateService#issue} — this method just embeds it, it doesn't
     * generate or validate it (that's the caller's job, done once up front and once again
     * when the resulting code comes back to {@link #connect}).
     */
    public String buildAuthorizeUrl(CalendarProvider provider, String redirectUri, String state) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(authorizeUrl(provider))
            .queryParam("client_id", clientId(provider))
            .queryParam("redirect_uri", redirectUri)
            .queryParam("response_type", "code")
            .queryParam("scope", scope(provider))
            .queryParam("state", state);
        if (provider == CalendarProvider.GOOGLE) {
            // access_type=offline + prompt=consent: without both, Google only returns a
            // refresh_token on a user's *first* consent ever, which breaks reconnecting.
            builder.queryParam("access_type", "offline").queryParam("prompt", "consent");
        }
        return builder.build().toUriString();
    }

    @Transactional
    public UserCalendarConnection connect(UUID userId, UUID tenantId, CalendarProvider provider,
                                           String authorizationCode, String redirectUri) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (!user.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId(provider));
        form.add("client_secret", clientSecret(provider));

        JsonNode tokenResponse = postForm(tokenUrl(provider), form);
        String refreshToken = tokenResponse.path("refresh_token").asText(null);
        if (refreshToken == null) {
            throw new IllegalStateException("Calendar provider did not return a refresh_token for provider=" + provider);
        }

        UserCalendarConnection connection = connectionRepository.findByUserIdAndProvider(userId, provider)
            .orElseGet(() -> UserCalendarConnection.builder().userId(userId).tenantId(tenantId).provider(provider).build());
        connection.setTenantId(tenantId);
        connection.setEncryptedRefreshToken(tokenEncryptor.encrypt(refreshToken));

        UserCalendarConnection saved = connectionRepository.save(connection);
        log.info("Connected calendar userId={} tenantId={} provider={}", userId, tenantId, provider);
        return saved;
    }

    /** Exchanges the stored refresh token for a short-lived access token. Not persisted — fetched on demand. */
    String refreshAccessToken(UserCalendarConnection connection) {
        String refreshToken = tokenEncryptor.decrypt(connection.getEncryptedRefreshToken());
        CalendarProvider provider = connection.getProvider();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId(provider));
        form.add("client_secret", clientSecret(provider));

        JsonNode tokenResponse = postForm(tokenUrl(provider), form);
        String accessToken = tokenResponse.path("access_token").asText(null);
        if (accessToken == null) {
            throw new IllegalStateException("Calendar provider did not return an access_token for provider=" + provider);
        }
        return accessToken;
    }

    private JsonNode postForm(String url, MultiValueMap<String, String> form) {
        String body = restClientBuilder.build().post()
            .uri(url)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(String.class);
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse calendar token response", e);
        }
    }

    private String tokenUrl(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? GOOGLE_TOKEN_URL : OUTLOOK_TOKEN_URL;
    }

    private String authorizeUrl(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? GOOGLE_AUTHORIZE_URL : OUTLOOK_AUTHORIZE_URL;
    }

    private String scope(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? GOOGLE_SCOPE : OUTLOOK_SCOPE;
    }

    private String clientId(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? googleClientId : outlookClientId;
    }

    private String clientSecret(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? googleClientSecret : outlookClientSecret;
    }
}
