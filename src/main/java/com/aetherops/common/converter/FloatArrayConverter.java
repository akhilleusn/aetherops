package com.aetherops.common.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class FloatArrayConverter implements AttributeConverter<float[], String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(float[] array) {
        if (array == null) return null;
        try {
            return MAPPER.writeValueAsString(array);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize float[] to JSON", e);
        }
    }

    @Override
    public float[] convertToEntityAttribute(String json) {
        if (json == null) return new float[0];
        try {
            return MAPPER.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSON to float[]", e);
        }
    }
}
