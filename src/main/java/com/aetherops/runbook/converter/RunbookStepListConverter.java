package com.aetherops.runbook.converter;

import com.aetherops.runbook.model.RunbookStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class RunbookStepListConverter implements AttributeConverter<List<RunbookStep>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<RunbookStep> steps) {
        if (steps == null) return null;
        try {
            return MAPPER.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize runbook steps to JSON", e);
        }
    }

    @Override
    public List<RunbookStep> convertToEntityAttribute(String json) {
        if (json == null) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSON to runbook steps", e);
        }
    }
}
