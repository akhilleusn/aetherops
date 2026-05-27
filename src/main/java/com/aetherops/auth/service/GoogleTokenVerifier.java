package com.aetherops.auth.service;

import com.aetherops.auth.model.GoogleUserInfo;
import com.aetherops.common.exception.GoogleAuthNotConfiguredException;
import com.aetherops.common.exception.InvalidGoogleTokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@Slf4j
public class GoogleTokenVerifier {

    private static final String TOKENINFO_URL =
        "https://oauth2.googleapis.com/tokeninfo?id_token={token}";

    private final String clientId;
    private final RestTemplate restTemplate;

    public GoogleTokenVerifier(
        @Value("${app.google.client-id:}") String clientId,
        RestTemplateBuilder builder
    ) {
        this.clientId = clientId;
        this.restTemplate = builder.build();
    }

    public boolean isConfigured() {
        return !clientId.isBlank();
    }

    public String getClientId() {
        return isConfigured() ? clientId : null;
    }

    @SuppressWarnings("unchecked")
    public GoogleUserInfo verify(String idToken) {
        if (!isConfigured()) {
            throw new GoogleAuthNotConfiguredException();
        }
        try {
            Map<String, Object> payload = restTemplate.getForObject(
                TOKENINFO_URL, Map.class, idToken);

            if (payload == null) {
                throw new InvalidGoogleTokenException();
            }

            String aud = (String) payload.get("aud");
            if (!clientId.equals(aud)) {
                log.warn("Google token audience mismatch: expected={} got={}", clientId, aud);
                throw new InvalidGoogleTokenException();
            }

            String sub   = (String) payload.get("sub");
            String email = (String) payload.get("email");
            if (sub == null || email == null) {
                throw new InvalidGoogleTokenException();
            }
            return new GoogleUserInfo(sub, email);

        } catch (RestClientException e) {
            log.warn("Google token verification failed: {}", e.getMessage());
            throw new InvalidGoogleTokenException();
        }
    }
}
