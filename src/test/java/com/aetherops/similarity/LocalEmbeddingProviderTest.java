package com.aetherops.similarity;

import com.aetherops.similarity.service.LocalEmbeddingProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LocalEmbeddingProviderTest {

    private final LocalEmbeddingProvider provider = new LocalEmbeddingProvider();

    @Test
    void embed_nullText_returnsZeroVector() {
        float[] v = provider.embed(null);
        assertThat(v).hasSize(LocalEmbeddingProvider.dimensions());
        assertThat(LocalEmbeddingProvider.isZeroVector(v)).isTrue();
    }

    @Test
    void embed_blankText_returnsZeroVector() {
        float[] v = provider.embed("   ");
        assertThat(LocalEmbeddingProvider.isZeroVector(v)).isTrue();
    }

    @Test
    void embed_textWithKnownKeywords_hasNonZeroComponents() {
        float[] v = provider.embed("database connection pool timeout redis cache");
        assertThat(LocalEmbeddingProvider.isZeroVector(v)).isFalse();
    }

    @Test
    void embed_sameTextTwice_isDeterministic() {
        String text = "HikariCP database connection pool exhausted timeout payment-svc";
        float[] v1 = provider.embed(text);
        float[] v2 = provider.embed(text);
        for (int i = 0; i < v1.length; i++) {
            assertThat(v1[i]).isEqualTo(v2[i]);
        }
    }

    @Test
    void embed_textWithKeywords_isUnitVector() {
        float[] v = provider.embed("database connection pool redis cache kafka timeout");
        double norm = 0.0;
        for (float f : v) norm += (double) f * f;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void cosineSimilarity_identicalUnitVectors_returnsOne() {
        float[] v = provider.embed("database connection pool timeout");
        double sim = LocalEmbeddingProvider.cosineSimilarity(v, v);
        assertThat(sim).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void cosineSimilarity_differentTexts_returnsLowerScore() {
        float[] db  = provider.embed("database connection pool hikari timeout postgres query");
        float[] msg = provider.embed("kafka consumer lag partition offset topic producer");
        double sim = LocalEmbeddingProvider.cosineSimilarity(db, msg);
        // These are unrelated SRE topics; similarity should be low
        assertThat(sim).isLessThan(0.5);
    }

    @Test
    void cosineSimilarity_similarTexts_returnsHighScore() {
        float[] a = provider.embed("database connection pool timeout redis cache eviction");
        float[] b = provider.embed("database connection timeout pool saturation redis miss");
        double sim = LocalEmbeddingProvider.cosineSimilarity(a, b);
        assertThat(sim).isGreaterThan(0.5);
    }

    @Test
    void modelName_returnsExpectedValue() {
        assertThat(provider.modelName()).isEqualTo(LocalEmbeddingProvider.MODEL_NAME);
    }
}
