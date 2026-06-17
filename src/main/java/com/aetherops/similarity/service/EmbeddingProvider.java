package com.aetherops.similarity.service;

public interface EmbeddingProvider {

    /**
     * Embed the given text into a float vector.
     * Implementations must return a unit-normalized vector so that the dot
     * product of two vectors equals their cosine similarity.
     */
    float[] embed(String text);

    default String modelName() {
        return "unknown";
    }
}
