package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * RestClient's fluent spec interfaces are self-typed generics (S extends RequestHeadersSpec&lt;S&gt;),
 * which RETURNS_DEEP_STUBS handles unreliably (chained calls silently return null). Mocking the
 * concrete spec type directly with Answers.RETURNS_SELF sidesteps that entirely: every unstubbed
 * builder call returns the same mock, matching real fluent behavior; only the two calls that
 * change type (.retrieve() and .body(String.class)) need explicit stubs.
 */
@ExtendWith(MockitoExtension.class)
class SlackConversationServiceTest {

    @Mock private SlackTokenEncryptor tokenEncryptor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private SlackConversationService service;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        lenient().when(builder.build()).thenReturn(mockRestClient);
        service = new SlackConversationService(tokenEncryptor, objectMapper, builder);
    }

    private void stubPostResponse(String jsonResponse) {
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(jsonResponse);
        when(mockRestClient.post()).thenReturn(bodySpec);
    }

    private void stubPostThrows(RuntimeException ex) {
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(ex);
        when(mockRestClient.post()).thenReturn(bodySpec);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubGetResponse(String jsonResponse) {
        RestClient.RequestHeadersUriSpec headersSpec = mock(RestClient.RequestHeadersUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(jsonResponse);
        when(mockRestClient.get()).thenReturn(headersSpec);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubGetThrows(RuntimeException ex) {
        RestClient.RequestHeadersUriSpec headersSpec = mock(RestClient.RequestHeadersUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(ex);
        when(mockRestClient.get()).thenReturn(headersSpec);
    }

    private SlackWorkspace workspaceWithChannel(String channelId) {
        return SlackWorkspace.builder()
            .tenantId(UUID.randomUUID())
            .encryptedBotToken("encrypted-token")
            .defaultChannelId(channelId)
            .build();
    }

    private Incident incident() {
        return Incident.builder()
            .id(UUID.randomUUID())
            .title("DB down")
            .severity(IncidentSeverity.CRITICAL)
            .serviceId("db-svc")
            .build();
    }

    @Test
    void postIncidentAlert_noDefaultChannel_returnsEmpty() {
        SlackWorkspace workspace = workspaceWithChannel(null);
        Optional<SlackConversationService.PostedMessage> result = service.postIncidentAlert(workspace, incident());
        assertThat(result).isEmpty();
        verifyNoInteractions(tokenEncryptor);
    }

    @Test
    void postIncidentAlert_success_returnsChannelAndTs() {
        SlackWorkspace workspace = workspaceWithChannel("C123");
        when(tokenEncryptor.decrypt("encrypted-token")).thenReturn("xoxb-token");
        stubPostResponse("{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1234.5678\"}");

        Optional<SlackConversationService.PostedMessage> result = service.postIncidentAlert(workspace, incident());

        assertThat(result).isPresent();
        assertThat(result.get().channel()).isEqualTo("C123");
        assertThat(result.get().ts()).isEqualTo("1234.5678");
    }

    @Test
    void postIncidentAlert_slackReturnsError_returnsEmpty() {
        SlackWorkspace workspace = workspaceWithChannel("C123");
        when(tokenEncryptor.decrypt("encrypted-token")).thenReturn("xoxb-token");
        stubPostResponse("{\"ok\":false,\"error\":\"channel_not_found\"}");

        Optional<SlackConversationService.PostedMessage> result = service.postIncidentAlert(workspace, incident());

        assertThat(result).isEmpty();
    }

    @Test
    void postIncidentAlert_httpException_returnsEmptyRatherThanThrowing() {
        SlackWorkspace workspace = workspaceWithChannel("C123");
        when(tokenEncryptor.decrypt("encrypted-token")).thenReturn("xoxb-token");
        stubPostThrows(new RuntimeException("network error"));

        Optional<SlackConversationService.PostedMessage> result = service.postIncidentAlert(workspace, incident());

        assertThat(result).isEmpty();
    }

    @Test
    void fetchThreadTranscript_success_returnsMessageTexts() {
        SlackWorkspace workspace = workspaceWithChannel("C123");
        when(tokenEncryptor.decrypt("encrypted-token")).thenReturn("xoxb-token");
        stubGetResponse("{\"ok\":true,\"messages\":[{\"text\":\"first message\"},{\"text\":\"second message\"}]}");

        var lines = service.fetchThreadTranscript(workspace, "C123", "1234.5678");

        assertThat(lines).containsExactly("first message", "second message");
    }

    @Test
    void fetchThreadTranscript_skipsBlankMessages() {
        SlackWorkspace workspace = workspaceWithChannel("C123");
        when(tokenEncryptor.decrypt("encrypted-token")).thenReturn("xoxb-token");
        stubGetResponse("{\"ok\":true,\"messages\":[{\"text\":\"\"},{\"text\":\"real message\"}]}");

        var lines = service.fetchThreadTranscript(workspace, "C123", "1234.5678");

        assertThat(lines).containsExactly("real message");
    }

    @Test
    void fetchThreadTranscript_slackError_returnsEmptyList() {
        SlackWorkspace workspace = workspaceWithChannel("C123");
        when(tokenEncryptor.decrypt("encrypted-token")).thenReturn("xoxb-token");
        stubGetResponse("{\"ok\":false,\"error\":\"thread_not_found\"}");

        var lines = service.fetchThreadTranscript(workspace, "C123", "1234.5678");

        assertThat(lines).isEmpty();
    }

    @Test
    void fetchThreadTranscript_httpException_returnsEmptyListRatherThanThrowing() {
        SlackWorkspace workspace = workspaceWithChannel("C123");
        when(tokenEncryptor.decrypt("encrypted-token")).thenReturn("xoxb-token");
        stubGetThrows(new RuntimeException("network error"));

        var lines = service.fetchThreadTranscript(workspace, "C123", "1234.5678");

        assertThat(lines).isEmpty();
    }
}
