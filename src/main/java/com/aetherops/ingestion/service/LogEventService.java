package com.aetherops.ingestion.service;

import com.aetherops.incident.service.IncidentGroupingService;
import com.aetherops.ingestion.model.LogEvent;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogEventResponse;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.masking.service.SecretMaskingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogEventService {

    private final LogEventRepository logEventRepository;
    private final SecretMaskingService secretMaskingService;
    private final IncidentGroupingService incidentGroupingService;

    @Transactional
    public LogEventResponse ingest(LogEventRequest request) {
        String maskedMessage = secretMaskingService.mask(request.getMessage());
        String maskedPayload = request.getRawPayload() != null
            ? secretMaskingService.mask(request.getRawPayload())
            : null;

        LogEvent event = LogEvent.builder()
            .tenantId(request.getTenantId())
            .projectId(request.getProjectId())
            .serviceId(request.getServiceId())
            .environment(request.getEnvironment())
            .level(request.getLevel())
            .message(maskedMessage)
            .rawPayload(maskedPayload)
            .traceId(request.getTraceId())
            .spanId(request.getSpanId())
            .occurredAt(request.getOccurredAt())
            .build();

        LogEvent saved = logEventRepository.save(event);
        log.info("Ingested log event id={} tenant={} service={} level={}",
            saved.getId(), saved.getTenantId(), saved.getServiceId(), saved.getLevel());

        incidentGroupingService.findOrCreate(saved);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<LogEventResponse> findAll(UUID tenantId, String serviceId, LogLevel level, Pageable pageable) {
        if (serviceId != null && level != null) {
            return logEventRepository
                .findByTenantIdAndServiceIdAndLevel(tenantId, serviceId, level, pageable)
                .map(this::toResponse);
        }
        if (serviceId != null) {
            return logEventRepository
                .findByTenantIdAndServiceId(tenantId, serviceId, pageable)
                .map(this::toResponse);
        }
        if (level != null) {
            return logEventRepository
                .findByTenantIdAndLevel(tenantId, level, pageable)
                .map(this::toResponse);
        }
        return logEventRepository.findByTenantId(tenantId, pageable).map(this::toResponse);
    }

    private LogEventResponse toResponse(LogEvent event) {
        return LogEventResponse.builder()
            .id(event.getId())
            .tenantId(event.getTenantId())
            .projectId(event.getProjectId())
            .serviceId(event.getServiceId())
            .environment(event.getEnvironment())
            .level(event.getLevel())
            .message(event.getMessage())
            .traceId(event.getTraceId())
            .spanId(event.getSpanId())
            .occurredAt(event.getOccurredAt())
            .ingestedAt(event.getIngestedAt())
            .build();
    }
}
