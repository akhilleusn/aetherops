package com.aetherops.oncall;

import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.aetherops.integration.twilio.TwilioConfig;

import java.util.UUID;

@Service
@Slf4j
public class OnCallService {
    @Autowired
    private OnCallRotationRepository rotationRepository;
    @Autowired
    private TwilioConfig twilioConfig;

    @Async
    public void pageOnCall(UUID tenantId, String serviceId, String incidentTitle) {
        try {
            OnCallRotation rotation = rotationRepository.findByTenantIdAndServiceId(tenantId, serviceId);
            if (rotation == null) {
                log.warn("No rotation found for service");
                return;
            }
            String phoneNumber = rotation.currentOnCallPhoneNumber();
            if (phoneNumber == null) {
                log.warn("No on-call engineer available");
                return;
            }
            Message.creator(new PhoneNumber(phoneNumber), new PhoneNumber(twilioConfig.getFromNumber()), "ALERT: " + incidentTitle).create();
            log.info("SMS paged to on-call engineer");
        } catch (Exception e) {
            log.error("Failed to page on-call engineer", e);
        }
    }
}
