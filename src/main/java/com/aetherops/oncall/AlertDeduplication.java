package com.aetherops.oncall;

import com.aetherops.common.FingerprintUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Groups alerts that fire repeatedly for the same service/alert/severity so a
 * flapping check doesn't create a fresh incident on every occurrence.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertDeduplication {

    private final AlertDeduplicationRepository repository;

    public record Alert(UUID tenantId, String service, String alertName, SeverityLevel severity) {}

    public String fingerprint(Alert alert) {
        String raw = alert.service() + "|" + alert.alertName() + "|" + alert.severity();
        return FingerprintUtil.sha256Hex(raw);
    }

    /**
     * Records an alert occurrence. Returns the deduplication row: callers should
     * only create a new incident when {@link DeduplicatedAlert#getAlertCount()} is 1
     * (first sighting), or when {@link DeduplicatedAlert#getIncidentId()} is null.
     */
    @Transactional
    public DeduplicatedAlert record(Alert alert) {
        String fingerprint = fingerprint(alert);
        Instant now = Instant.now();

        return repository.findByTenantIdAndFingerprint(alert.tenantId(), fingerprint)
                .map(existing -> {
                    existing.setAlertCount(existing.getAlertCount() + 1);
                    existing.setLastSeenAt(now);
                    return repository.save(existing);
                })
                .orElseGet(() -> createNew(alert, fingerprint, now));
    }

    private DeduplicatedAlert createNew(Alert alert, String fingerprint, Instant now) {
        DeduplicatedAlert dedup = DeduplicatedAlert.builder()
                .tenantId(alert.tenantId())
                .fingerprint(fingerprint)
                .service(alert.service())
                .alertName(alert.alertName())
                .severity(alert.severity())
                .alertCount(1)
                .lastSeenAt(now)
                .build();
        try {
            return repository.save(dedup);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against another concurrent insert on (tenant_id, fingerprint).
            log.debug("Concurrent alert dedup insert for fingerprint {}, retrying as update", fingerprint);
            return repository.findByTenantIdAndFingerprint(alert.tenantId(), fingerprint)
                    .map(existing -> {
                        existing.setAlertCount(existing.getAlertCount() + 1);
                        existing.setLastSeenAt(now);
                        return repository.save(existing);
                    })
                    .orElseThrow(() -> e);
        }
    }

    @Transactional
    public void linkIncident(DeduplicatedAlert dedup, UUID incidentId) {
        dedup.setIncidentId(incidentId);
        repository.save(dedup);
    }
}
