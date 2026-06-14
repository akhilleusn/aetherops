package com.aetherops.incident.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void publish(Object domainEvent) {
        log.debug("Publishing domain event type={}", domainEvent.getClass().getSimpleName());
        applicationEventPublisher.publishEvent(domainEvent);
    }
}
