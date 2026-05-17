package com.aetherops.ingestion.service;

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

@Service
@RequiredArgsConstructor
@Slf4j
public class LogEventService {

    private final LogEventRepository logEventRepository;
    private final SecretMaskingService secretMaskingService;

    @Transactional
    public LogEventResponse ingest(LogEventRequest request) {
        String maskedMessage = secretMaskingService.mask(request.getMessage());
        String maskedPayload = request.getRawPayload() != null
            ? secretMaskingService.mask(request.getRawPayload())
            : null;

        LogEvent event = LogEvent.builder()
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
        log.info("Ingested log event id={} service={} level={}", saved.getId(), saved.getServiceId(), saved.getLevel());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<LogEventResponse> findAll(String serviceId, LogLevel level, Pageable pageable) {
        if (serviceId != null && level != null) {
            return logEventRepository.findByServiceIdAndLevel(serviceId, level, pageable).map(this::toResponse);
        }
        if (serviceId != null) {
            return logEventRepository.findByServiceId(serviceId, pageable).map(this::toResponse);
        }
        if (level != null) {
            return logEventRepository.findByLevel(level, pageable).map(this::toResponse);
        }
        return logEventRepository.findAll(pageable).map(this::toResponse);
    }

    private LogEventResponse toResponse(LogEvent event) {
        return LogEventResponse.builder()
            .id(event.getId())
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
