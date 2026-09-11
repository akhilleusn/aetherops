package com.aetherops.oncall;

import com.aetherops.calendar.PtoAwarenessService;
import com.aetherops.integration.twilio.TwilioConfig;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class OnCallService {
    @Autowired
    private OnCallRotationRepository rotationRepository;
    @Autowired
    private TwilioConfig twilioConfig;
    @Autowired
    private PtoAwarenessService ptoAwarenessService;

    /**
     * Step G1 (see STEP_REPORT.md): what actually happened when a page was attempted --
     * distinguishes "the provider accepted the send request" from every reason it might not
     * have, so a caller can decide what to do next instead of the outcome being silently
     * swallowed. {@code ACCEPTED} deliberately does NOT mean "delivered" -- Twilio's
     * {@code create()} response only confirms the provider queued/accepted the message; actual
     * handset delivery is asynchronous and not observed by this call at all (see the class
     * javadoc on the status-callback-webhook follow-up this would need).
     */
    public enum PageOutcome { ACCEPTED, NOT_CONFIGURED, NO_ROTATION, NO_ENGINEER_AVAILABLE, PROVIDER_ERROR }

    public record PageResult(PageOutcome outcome, String providerMessageSid, String detail) {
        public boolean succeeded() {
            return outcome == PageOutcome.ACCEPTED;
        }
    }

    /**
     * No longer {@code @Async} (Step G1) -- a fire-and-forget void that swallows its own
     * exception is exactly why a permanently-broken SMS provider went unnoticed: nothing above
     * this method could ever observe the failure. Now synchronous and returns a {@link PageResult}
     * so every caller decides its own concurrency strategy and actually records what happened.
     */
    public PageResult pageOnCall(UUID tenantId, String serviceId, String incidentTitle) {
        if (!twilioConfig.isConfigured()) {
            log.warn("Twilio not configured -- cannot page on-call engineer serviceId={}", serviceId);
            return new PageResult(PageOutcome.NOT_CONFIGURED, null, "Twilio credentials not configured");
        }

        OnCallRotation rotation = rotationRepository.findByTenantIdAndServiceId(tenantId, serviceId);
        if (rotation == null) {
            log.warn("No rotation found for service");
            return new PageResult(PageOutcome.NO_ROTATION, null, "No on-call rotation configured for " + serviceId);
        }

        String phoneNumber = rotation.currentOnCallPhoneNumber(
            userId -> ptoAwarenessService.isUserAvailable(userId, Instant.now()));
        if (phoneNumber == null) {
            log.warn("No on-call engineer available");
            return new PageResult(PageOutcome.NO_ENGINEER_AVAILABLE, null, "No on-call engineer available");
        }

        try {
            Message message = Message.creator(new PhoneNumber(phoneNumber), new PhoneNumber(twilioConfig.getFromNumber()),
                "ALERT: " + incidentTitle).create();
            log.info("SMS accepted by Twilio sid={} status={}", message.getSid(), message.getStatus());
            return new PageResult(PageOutcome.ACCEPTED, message.getSid(),
                message.getStatus() != null ? message.getStatus().toString() : null);
        } catch (Exception e) {
            log.error("Failed to page on-call engineer via Twilio", e);
            return new PageResult(PageOutcome.PROVIDER_ERROR, null, e.getMessage());
        }
    }
}
