package com.aetherops.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

import com.aetherops.incident.event.IncidentCreatedEvent;
import com.aetherops.incident.event.IncidentEventPublisher;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {IncidentEventPublisher.TOPIC})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.jwt.secret=test-secret-key-minimum-32-characters-long",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6380"
})
@DirtiesContext
class IncidentEventPublisherTest {

    @Autowired
    private IncidentEventPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    private final CountDownLatch latch = new CountDownLatch(1);
    private String receivedMessage;

    @KafkaListener(topics = IncidentEventPublisher.TOPIC, groupId = "test-group")
    public void receive(ConsumerRecord<String, String> record) {
        receivedMessage = record.value();
        latch.countDown();
    }

    @Test
    void publish_sendsEventToKafkaTopic() throws Exception {
        IncidentCreatedEvent event = IncidentCreatedEvent.builder()
                .incidentId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .severity("HIGH")
                .title("Test incident")
                .occurredAt(Instant.now())
                .build();

        publisher.publish(event);

        boolean messageReceived = latch.await(10, TimeUnit.SECONDS);
        assertThat(messageReceived).isTrue();

        IncidentCreatedEvent received = objectMapper.readValue(receivedMessage, IncidentCreatedEvent.class);
        assertThat(received.getIncidentId()).isEqualTo(event.getIncidentId());
        assertThat(received.getSeverity()).isEqualTo("HIGH");
        assertThat(received.getTitle()).isEqualTo("Test incident");
    }
}