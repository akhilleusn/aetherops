package com.aetherops.slack;

import com.aetherops.oncall.OnCallService;
import com.aetherops.oncall.OnCallService.PageOutcome;
import com.aetherops.oncall.OnCallService.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Step G1 (see STEP_REPORT.md): the manual {@code /page} Slack command must report the real
 * paging outcome back via Slack's {@code response_url}, not leave the caller wondering the way
 * the old fire-and-forget {@code onCallService.pageOnCall(...)} call used to.
 */
@ExtendWith(MockitoExtension.class)
class SlackPageResponseServiceTest {

    @Mock private OnCallService onCallService;
    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private SlackPageResponseService service;

    private final UUID tenantId = UUID.randomUUID();
    private static final String RESPONSE_URL = "https://hooks.slack.com/commands/T1/999/abc";

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        lenient().when(builder.build()).thenReturn(mockRestClient);
        service = new SlackPageResponseService(onCallService, builder);
    }

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodyUriSpec stubPostChain() {
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(mockRestClient.post()).thenReturn(bodySpec);
        return bodySpec;
    }

    @Test
    void pageAndRespond_success_postsSuccessMessageToResponseUrl() {
        when(onCallService.pageOnCall(tenantId, "db-svc", "fire"))
            .thenReturn(new PageResult(PageOutcome.ACCEPTED, "SM123", "queued"));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        service.pageAndRespond(tenantId, "db-svc", "fire", RESPONSE_URL);

        verify(bodySpec).uri(RESPONSE_URL);
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue().get("text")).contains("✅").contains("db-svc");
    }

    @Test
    void pageAndRespond_notConfigured_postsFailureMessageExplainingWhy() {
        when(onCallService.pageOnCall(tenantId, "db-svc", "fire"))
            .thenReturn(new PageResult(PageOutcome.NOT_CONFIGURED, null, "Twilio credentials not configured"));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        service.pageAndRespond(tenantId, "db-svc", "fire", RESPONSE_URL);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue().get("text")).contains("❌").contains("isn't configured");
    }

    @Test
    void pageAndRespond_noRotation_postsFailureMessageExplainingWhy() {
        when(onCallService.pageOnCall(tenantId, "db-svc", "fire"))
            .thenReturn(new PageResult(PageOutcome.NO_ROTATION, null, "No on-call rotation configured for db-svc"));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        service.pageAndRespond(tenantId, "db-svc", "fire", RESPONSE_URL);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue().get("text")).contains("no on-call rotation");
    }

    @Test
    void pageAndRespond_providerError_includesDetailInMessage() {
        when(onCallService.pageOnCall(tenantId, "db-svc", "fire"))
            .thenReturn(new PageResult(PageOutcome.PROVIDER_ERROR, null, "rate limited"));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        service.pageAndRespond(tenantId, "db-svc", "fire", RESPONSE_URL);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue().get("text")).contains("rate limited");
    }

    @Test
    void pageAndRespond_responseUrlPostFails_doesNotThrow() {
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.ACCEPTED, "SM123", "queued"));
        when(mockRestClient.post()).thenThrow(new RuntimeException("network error"));

        service.pageAndRespond(tenantId, "db-svc", "fire", RESPONSE_URL);
        // no exception propagated
    }

    @Test
    void pageAndRespond_responseTypeIsEphemeral() {
        when(onCallService.pageOnCall(any(), any(), any()))
            .thenReturn(new PageResult(PageOutcome.ACCEPTED, "SM123", "queued"));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        service.pageAndRespond(tenantId, "db-svc", "fire", RESPONSE_URL);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue()).containsEntry("response_type", "ephemeral");
    }
}
