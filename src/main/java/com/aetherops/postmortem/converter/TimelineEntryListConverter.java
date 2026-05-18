package com.aetherops.postmortem.converter;

import com.aetherops.postmortem.model.TimelineEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class TimelineEntryListConverter implements AttributeConverter<List<TimelineEntry>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public String convertToDatabaseColumn(List<TimelineEntry> entries) {
        if (entries == null) return null;
        try {
            return MAPPER.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize timeline entries to JSON", e);
        }
    }

    @Override
    public List<TimelineEntry> convertToEntityAttribute(String json) {
        if (json == null) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSON to timeline entries", e);
        }
    }
}
