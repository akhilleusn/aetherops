package com.aetherops.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aetherops.auth.model.GoogleUserInfo;
import com.aetherops.common.exception.InvalidGoogleTokenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 1 Step C, item 5: GoogleTokenVerifier checked aud but never read email_verified —
 * an unverified email means Google hasn't confirmed the caller owns it, so accepting the token
 * anyway would let anyone claim an unverified address (including one matching an existing
 * AetherOps account, via GoogleAuthService's email-match linking).
 */
class GoogleTokenVerifierTest {

    private static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";
    private static final String ID_TOKEN = "fake-id-token";
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=" + ID_TOKEN;

    private GoogleTokenVerifier verifier;
    private MockRestServiceServer mockServer;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();

        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.build()).thenReturn(restTemplate);

        verifier = new GoogleTokenVerifier(CLIENT_ID, builder);

        logger = (Logger) LoggerFactory.getLogger(GoogleTokenVerifier.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(logAppender);
    }

    private List<String> loggedMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void verify_emailVerifiedMissing_throwsInvalidGoogleTokenException() {
        mockServer.expect(requestTo(TOKENINFO_URL))
            .andRespond(withSuccess(
                "{\"aud\":\"" + CLIENT_ID + "\",\"sub\":\"12345\",\"email\":\"user@example.com\"}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify(ID_TOKEN))
            .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void verify_emailVerifiedFalseString_throwsInvalidGoogleTokenException() {
        mockServer.expect(requestTo(TOKENINFO_URL))
            .andRespond(withSuccess(
                "{\"aud\":\"" + CLIENT_ID + "\",\"sub\":\"12345\",\"email\":\"user@example.com\",\"email_verified\":\"false\"}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify(ID_TOKEN))
            .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void verify_emailVerifiedTrueString_succeeds() {
        mockServer.expect(requestTo(TOKENINFO_URL))
            .andRespond(withSuccess(
                "{\"aud\":\"" + CLIENT_ID + "\",\"sub\":\"12345\",\"email\":\"user@example.com\",\"email_verified\":\"true\"}",
                MediaType.APPLICATION_JSON));

        GoogleUserInfo result = verifier.verify(ID_TOKEN);

        assertThat(result.sub()).isEqualTo("12345");
        assertThat(result.email()).isEqualTo("user@example.com");
    }

    @Test
    void verify_emailVerifiedBooleanTrue_succeeds() {
        // Defends against the case where the JSON literal `true` deserializes as a real
        // Boolean rather than Google's documented "true" string.
        mockServer.expect(requestTo(TOKENINFO_URL))
            .andRespond(withSuccess(
                "{\"aud\":\"" + CLIENT_ID + "\",\"sub\":\"12345\",\"email\":\"user@example.com\",\"email_verified\":true}",
                MediaType.APPLICATION_JSON));

        GoogleUserInfo result = verifier.verify(ID_TOKEN);

        assertThat(result.sub()).isEqualTo("12345");
    }

    /**
     * Phase 3 finding 7 (see STEP_REPORT.md, UNVERIFIED at audit time): the raw Google ID token
     * is embedded in the tokeninfo request URL. Spring's RestTemplate embeds the resolved
     * request URI in a RestClientException's message on I/O failure (e.g. ResourceAccessException
     * "I/O error on GET request for \"<url>\": ..."), so logging e.getMessage() unfiltered would
     * write the raw token to the logs on every network hiccup talking to Google. Forces exactly
     * that failure path (an I/O error on the request) and asserts the token never reaches the logs.
     */
    @Test
    void verify_networkFailure_neverLogsRawToken() {
        mockServer.expect(requestTo(TOKENINFO_URL))
            .andRespond(request -> { throw new IOException("Connection refused"); });

        assertThatThrownBy(() -> verifier.verify(ID_TOKEN))
            .isInstanceOf(InvalidGoogleTokenException.class);

        List<String> messages = loggedMessages();
        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(m -> m.contains(ID_TOKEN));
    }
}
