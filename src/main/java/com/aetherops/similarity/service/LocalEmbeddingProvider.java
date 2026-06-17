package com.aetherops.similarity.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic embedding that works without any external API.
 * Projects incident text onto a fixed SRE vocabulary and returns
 * a unit-normalized float vector. The dot product of two such vectors
 * equals their cosine similarity.
 *
 * Suitable as the default EmbeddingProvider.
 * Swap with a real embedding model by defining another @Primary @Component.
 */
@Component
public class LocalEmbeddingProvider implements EmbeddingProvider {

    public static final String MODEL_NAME = "local-tfidf-v1";

    // Fixed vocabulary: position → dimension index.
    // Terms chosen to cover common SRE incident patterns.
    static final String[] VOCABULARY = {
        // Database / storage
        "database", "postgres", "mysql", "connection", "pool", "query", "transaction",
        "timeout", "deadlock", "lock", "replication", "constraint", "index", "schema",
        "migration", "hikari", "jdbc",
        // Cache
        "redis", "cache", "eviction", "hit", "miss", "memcached", "ttl",
        // Messaging / async
        "kafka", "queue", "consumer", "producer", "offset", "partition", "lag", "topic",
        // Networking / HTTP
        "http", "request", "response", "latency", "retry", "circuit", "breaker",
        "proxy", "gateway", "ssl", "certificate", "dns",
        // JVM / application
        "exception", "error", "stacktrace", "nullpointer", "outofmemory", "heap",
        "thread", "cpu", "memory", "disk", "gc", "leak",
        // Operations / SRE
        "deploy", "rollback", "restart", "scale", "pod", "container", "kubernetes",
        "health", "alert", "pager", "oncall", "runbook", "postmortem", "saturation",
        "throughput", "p99", "sla",
        // Severity / status
        "critical", "high", "medium", "low", "resolved", "open", "investigating",
        // Service names likely in the demo
        "payment", "auth", "order", "inventory", "notification", "audit"
    };

    private static final Map<String, Integer> VOCAB_INDEX;

    static {
        VOCAB_INDEX = new HashMap<>(VOCABULARY.length * 2);
        for (int i = 0; i < VOCABULARY.length; i++) {
            VOCAB_INDEX.put(VOCABULARY[i], i);
        }
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[VOCABULARY.length];
        if (text == null || text.isBlank()) {
            return vector;
        }
        String[] tokens = text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .trim()
                .split("\\s+");

        for (String token : tokens) {
            Integer idx = VOCAB_INDEX.get(token);
            if (idx != null) {
                vector[idx] += 1.0f;
            }
        }
        return normalize(vector);
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    /** Cosine similarity of two unit vectors = their dot product. */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    /** Returns true when the vector is all zeros (no vocabulary terms matched). */
    public static boolean isZeroVector(float[] v) {
        for (float f : v) {
            if (f != 0.0f) return false;
        }
        return true;
    }

    public static int dimensions() {
        return VOCABULARY.length;
    }

    private static float[] normalize(float[] v) {
        double sumSq = 0.0;
        for (float f : v) sumSq += (double) f * f;
        if (sumSq == 0.0) return v;
        float scale = (float) (1.0 / Math.sqrt(sumSq));
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] * scale;
        }
        return result;
    }
}
