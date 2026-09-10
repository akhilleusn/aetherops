package com.aetherops.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.*;

/**
 * Phase 2 remediation, finding 11 (see STEP_REPORT.md): {@code respondToInteraction} must
 * refuse to POST anywhere that isn't a genuine {@code hooks.slack.com} URL, independent of
 * {@code SlackSignatureFilter}'s own verification — defense in depth, matching why
 * {@code AwsSnsHostValidator} exists as a second check for SNS.
 */
@ExtendWith(MockitoExtension.class)
class SlackApiClientTest {

    @Mock private RestClient.Builder restClientBuilder;

    private SlackApiClient client;

    @BeforeEach
    void setUp() {
        SlackAppConfig config = new SlackAppConfig("client-id", "client-secret", "signing-secret", "http://localhost:8080/slack/oauth/callback");
        client = new SlackApiClient(config, new ObjectMapper(), restClientBuilder);
    }

    @Test
    void respondToInteraction_genuineHooksSlackComUrl_posted() {
        RestClient mockRestClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClientBuilder.build()).thenReturn(mockRestClient);
        when(mockRestClient.post()).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);

        client.respondToInteraction("https://hooks.slack.com/actions/T00/B00/xyz", "ack");

        verify(mockRestClient).post();
    }

    @Test
    void respondToInteraction_privateRangeUrl_rejectedWithoutCallingRestClient() {
        client.respondToInteraction("https://10.0.0.5/actions/xyz", "ack");

        verifyNoInteractions(restClientBuilder);
    }

    @Test
    void respondToInteraction_linkLocalMetadataUrl_rejected() {
        client.respondToInteraction("https://169.254.169.254/actions/xyz", "ack");

        verifyNoInteractions(restClientBuilder);
    }

    @Test
    void respondToInteraction_httpUrl_rejected() {
        client.respondToInteraction("http://hooks.slack.com/actions/xyz", "ack");

        verifyNoInteractions(restClientBuilder);
    }

    @Test
    void respondToInteraction_lookalikeHost_rejected() {
        // A host that merely contains "hooks.slack.com" as a substring/suffix trick, not the
        // genuine host — e.g. an attacker-registered domain designed to fool a naive check.
        client.respondToInteraction("https://hooks.slack.com.evil.com/actions/xyz", "ack");

        verifyNoInteractions(restClientBuilder);
    }
}
