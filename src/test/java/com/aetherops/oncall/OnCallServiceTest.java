package com.aetherops.oncall;

import com.aetherops.calendar.PtoAwarenessService;
import com.aetherops.integration.twilio.TwilioConfig;
import com.aetherops.oncall.OnCallService.PageOutcome;
import com.aetherops.oncall.OnCallService.PageResult;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Step G1 (see STEP_REPORT.md): this class had zero test coverage before this batch --
 * {@code IncidentPagingServiceTest}/{@code EscalationServiceTest} both mocked
 * {@code OnCallService} out entirely, so nothing ever exercised the real Twilio-calling code,
 * which is exactly why the SDK was never actually initialized for months without anyone
 * noticing.
 */
@ExtendWith(MockitoExtension.class)
class OnCallServiceTest {

    @Mock private OnCallRotationRepository rotationRepository;
    @Mock private TwilioConfig twilioConfig;
    @Mock private PtoAwarenessService ptoAwarenessService;

    private OnCallService onCallService;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        onCallService = new OnCallService();
        ReflectionTestUtils.setField(onCallService, "rotationRepository", rotationRepository);
        ReflectionTestUtils.setField(onCallService, "twilioConfig", twilioConfig);
        ReflectionTestUtils.setField(onCallService, "ptoAwarenessService", ptoAwarenessService);
    }

    private OnCallRotation rotationWithParticipant(String phoneNumber) {
        return OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("db-svc")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of(phoneNumber))
            .build();
    }

    @Test
    void pageOnCall_twilioNotConfigured_returnsNotConfiguredWithoutTouchingRotation() {
        when(twilioConfig.isConfigured()).thenReturn(false);

        PageResult result = onCallService.pageOnCall(tenantId, "db-svc", "title");

        assertThat(result.outcome()).isEqualTo(PageOutcome.NOT_CONFIGURED);
        assertThat(result.succeeded()).isFalse();
        verifyNoInteractions(rotationRepository);
    }

    @Test
    void pageOnCall_noRotationForService_returnsNoRotation() {
        when(twilioConfig.isConfigured()).thenReturn(true);
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(null);

        PageResult result = onCallService.pageOnCall(tenantId, "db-svc", "title");

        assertThat(result.outcome()).isEqualTo(PageOutcome.NO_ROTATION);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void pageOnCall_rotationWithNoParticipants_returnsNoEngineerAvailable() {
        when(twilioConfig.isConfigured()).thenReturn(true);
        OnCallRotation rotation = OnCallRotation.builder()
            .id(1L).tenantId(tenantId).serviceId("db-svc")
            .rotationStartAt(Instant.now()).shiftLengthHours(24)
            .participants(List.of())
            .build();
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(rotation);

        PageResult result = onCallService.pageOnCall(tenantId, "db-svc", "title");

        assertThat(result.outcome()).isEqualTo(PageOutcome.NO_ENGINEER_AVAILABLE);
    }

    @Test
    void pageOnCall_twilioAccepts_returnsAcceptedWithMessageSid() {
        when(twilioConfig.isConfigured()).thenReturn(true);
        when(twilioConfig.getFromNumber()).thenReturn("+15550001111");
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc"))
            .thenReturn(rotationWithParticipant("+15550002222"));

        try (MockedStatic<Message> mockedMessage = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            Message messageMock = mock(Message.class);
            when(messageMock.getSid()).thenReturn("SM123abc");
            when(messageMock.getStatus()).thenReturn(Message.Status.QUEUED);
            mockedMessage.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                .thenReturn(creatorMock);
            when(creatorMock.create()).thenReturn(messageMock);

            PageResult result = onCallService.pageOnCall(tenantId, "db-svc", "title");

            assertThat(result.outcome()).isEqualTo(PageOutcome.ACCEPTED);
            assertThat(result.succeeded()).isTrue();
            assertThat(result.providerMessageSid()).isEqualTo("SM123abc");
            assertThat(result.detail()).isEqualTo("queued");
        }
    }

    @Test
    void pageOnCall_twilioThrows_returnsProviderErrorNotAnUncaughtException() {
        when(twilioConfig.isConfigured()).thenReturn(true);
        when(twilioConfig.getFromNumber()).thenReturn("+15550001111");
        when(rotationRepository.findByTenantIdAndServiceId(tenantId, "db-svc"))
            .thenReturn(rotationWithParticipant("+15550002222"));

        try (MockedStatic<Message> mockedMessage = mockStatic(Message.class)) {
            MessageCreator creatorMock = mock(MessageCreator.class);
            mockedMessage.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), anyString()))
                .thenReturn(creatorMock);
            when(creatorMock.create()).thenThrow(new ApiException(
                "TwilioRestClient was used before AccountSid and AuthToken were set, please call Twilio.init()"));

            PageResult result = onCallService.pageOnCall(tenantId, "db-svc", "title");

            assertThat(result.outcome()).isEqualTo(PageOutcome.PROVIDER_ERROR);
            assertThat(result.succeeded()).isFalse();
            assertThat(result.detail()).contains("Twilio.init()");
        }
    }
}
