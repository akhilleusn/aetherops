package com.aetherops.postmortem.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.postmortem.model.PostmortemContext;
import com.aetherops.postmortem.model.PostmortemDraftResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockPostmortemDraftGeneratorTest {

    private final MockPostmortemDraftGenerator generator = new MockPostmortemDraftGenerator();

    private AIAnalysisContext minimalContext() {
        return AIAnalysisContext.builder()
            .incidentTitle("DB down")
            .serviceId("db-svc")
            .severity("CRITICAL")
            .status("OPEN")
            .firstSeen(Instant.now())
            .lastSeen(Instant.now())
            .evidenceItems(List.of())
            .build();
    }

    @Test
    void generate_noSlackTranscript_summaryIsNull() {
        PostmortemContext context = PostmortemContext.builder()
            .incidentContext(minimalContext())
            .hasAnalysis(false)
            .build();

        PostmortemDraftResult result = generator.generate(context);

        assertThat(result.getSlackDiscussionSummary()).isNull();
    }

    @Test
    void generate_withSlackTranscript_summaryIncludesMessages() {
        PostmortemContext context = PostmortemContext.builder()
            .incidentContext(minimalContext())
            .hasAnalysis(false)
            .slackTranscript(List.of("we think it's the connection pool", "rolling back now"))
            .build();

        PostmortemDraftResult result = generator.generate(context);

        assertThat(result.getSlackDiscussionSummary())
            .contains("we think it's the connection pool")
            .contains("rolling back now")
            .contains("2 message(s)");
    }

    @Test
    void generate_longTranscript_capsAt20LinesAndNotesOmission() {
        List<String> longTranscript = java.util.stream.IntStream.range(0, 25)
            .mapToObj(i -> "message " + i)
            .toList();
        PostmortemContext context = PostmortemContext.builder()
            .incidentContext(minimalContext())
            .hasAnalysis(false)
            .slackTranscript(longTranscript)
            .build();

        PostmortemDraftResult result = generator.generate(context);

        assertThat(result.getSlackDiscussionSummary()).contains("5 further message(s) omitted");
        assertThat(result.getSlackDiscussionSummary()).doesNotContain("message 24");
    }
}
