package com.aetherops.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aetherops.incident.event.IncidentCreatedEvent;
import com.aetherops.incident.event.IncidentEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private IncidentEventPublisher publisher;

    @Test
    void publish_sendsEventToKafkaTopic() throws Exception {
        IncidentCreatedEvent event = IncidentCreatedEvent.builder()
                .incidentId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .severity("HIGH")
                .title("Test incident")
                .occurredAt(Instant.now())
                .build();

        String serialized = "{\"incidentId\":\"" + event.getIncidentId() + "\",\"severity\":\"HIGH\"}";
        when(objectMapper.writeValueAsString(event)).thenReturn(serialized);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq(IncidentEventPublisher.TOPIC),
                eq(event.getIncidentId().toString()),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue()).isEqualTo(serialized);
    }
}
