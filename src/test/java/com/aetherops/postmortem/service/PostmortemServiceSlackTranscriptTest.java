package com.aetherops.postmortem.service;

import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.postmortem.model.PostmortemContext;
import com.aetherops.postmortem.model.PostmortemDraftResult;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.slack.SlackConversationService;
import com.aetherops.slack.SlackWorkspace;
import com.aetherops.slack.SlackWorkspaceRepository;
import com.aetherops.usage.service.UsageLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostmortemServiceSlackTranscriptTest {

    @Mock private EvidencePackService evidencePackService;
    @Mock private AIAnalysisRepository aiAnalysisRepository;
    @Mock private PostmortemDraftGenerator draftGenerator;
    @Mock private PostmortemDraftRepository postmortemDraftRepository;
    @Mock private UsageLimitService usageLimitService;
    @Mock private AuditLogService auditLogService;
    @Mock private IncidentRepository incidentRepository;
    @Mock private SlackWorkspaceRepository slackWorkspaceRepository;
    @Mock private SlackConversationService slackConversationService;

    private PostmortemService postmortemService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Real evaluation service — pure logic, no mocking needed (see its own javadoc).
        postmortemService = new PostmortemService(
            evidencePackService, aiAnalysisRepository, draftGenerator,
            new PostmortemEvaluationService(), postmortemDraftRepository,
            usageLimitService, auditLogService, incidentRepository,
            slackWorkspaceRepository, slackConversationService);

        IncidentEvidencePackResponse pack = IncidentEvidencePackResponse.builder()
            .incidentId(incidentId).tenantId(tenantId).title("DB down")
            .serviceId("db-svc").totalEvidenceCount(0).evidence(List.of())
            .build();
        lenient().when(evidencePackService.getEvidencePack(incidentId, tenantId)).thenReturn(pack);
        lenient().when(aiAnalysisRepository.findTopByIncidentIdOrderByCreatedAtDesc(incidentId))
            .thenReturn(Optional.empty());
        lenient().when(draftGenerator.generate(any())).thenReturn(PostmortemDraftResult.builder()
            .incidentSummary("summary").likelyCause("cause").impact("impact")
            .dataGaps(List.of()).followUpActions(List.of()).timeline(List.of())
            .generatorVersion("test-v1").build());
        lenient().when(postmortemDraftRepository.saveAndFlush(any())).thenAnswer(i -> {
            var draft = i.getArgument(0, com.aetherops.postmortem.model.PostmortemDraft.class);
            draft.setId(UUID.randomUUID());
            draft.setCreatedAt(Instant.now());
            return draft;
        });
    }

    private Incident incidentWithoutSlackThread() {
        return Incident.builder().id(incidentId).tenantId(tenantId).title("DB down")
            .status(IncidentStatus.OPEN).severity(IncidentSeverity.CRITICAL).serviceId("db-svc").build();
    }

    private Incident incidentWithSlackThread() {
        return Incident.builder().id(incidentId).tenantId(tenantId).title("DB down")
            .status(IncidentStatus.OPEN).severity(IncidentSeverity.CRITICAL).serviceId("db-svc")
            .slackChannelId("C123").slackThreadTs("1111.2222").build();
    }

    @Test
    void generate_incidentWithNoSlackThread_passesEmptyTranscript() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentWithoutSlackThread()));

        postmortemService.generate(incidentId, tenantId);

        ArgumentCaptor<PostmortemContext> captor = ArgumentCaptor.forClass(PostmortemContext.class);
        verify(draftGenerator).generate(captor.capture());
        assertThat(captor.getValue().hasSlackTranscript()).isFalse();
        verifyNoInteractions(slackWorkspaceRepository, slackConversationService);
    }

    @Test
    void generate_threadLinkedButNoConnectedWorkspace_passesEmptyTranscript() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentWithSlackThread()));
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        postmortemService.generate(incidentId, tenantId);

        ArgumentCaptor<PostmortemContext> captor = ArgumentCaptor.forClass(PostmortemContext.class);
        verify(draftGenerator).generate(captor.capture());
        assertThat(captor.getValue().hasSlackTranscript()).isFalse();
        verifyNoInteractions(slackConversationService);
    }

    @Test
    void generate_threadLinkedAndWorkspaceConnected_fetchesTranscript() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentWithSlackThread()));
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).build();
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));
        when(slackConversationService.fetchThreadTranscript(workspace, "C123", "1111.2222"))
            .thenReturn(List.of("discussion line 1", "discussion line 2"));

        postmortemService.generate(incidentId, tenantId);

        ArgumentCaptor<PostmortemContext> captor = ArgumentCaptor.forClass(PostmortemContext.class);
        verify(draftGenerator).generate(captor.capture());
        assertThat(captor.getValue().getSlackTranscript())
            .containsExactly("discussion line 1", "discussion line 2");
    }

    @Test
    void generate_slackFetchThrows_generationStillSucceedsWithEmptyTranscript() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentWithSlackThread()));
        SlackWorkspace workspace = SlackWorkspace.builder().tenantId(tenantId).build();
        when(slackWorkspaceRepository.findByTenantId(tenantId)).thenReturn(Optional.of(workspace));
        when(slackConversationService.fetchThreadTranscript(any(), any(), any()))
            .thenThrow(new RuntimeException("Slack API down"));

        var response = postmortemService.generate(incidentId, tenantId);

        assertThat(response).isNotNull();
        ArgumentCaptor<PostmortemContext> captor = ArgumentCaptor.forClass(PostmortemContext.class);
        verify(draftGenerator).generate(captor.capture());
        assertThat(captor.getValue().hasSlackTranscript()).isFalse();
    }

    @Test
    void generate_persistsSlackDiscussionSummaryOntoDraft() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incidentWithoutSlackThread()));
        when(draftGenerator.generate(any())).thenReturn(PostmortemDraftResult.builder()
            .incidentSummary("summary").likelyCause("cause").impact("impact")
            .dataGaps(List.of()).followUpActions(List.of()).timeline(List.of())
            .generatorVersion("test-v1").slackDiscussionSummary("Highlights: fixed it").build());

        var response = postmortemService.generate(incidentId, tenantId);

        assertThat(response.getSlackDiscussionSummary()).isEqualTo("Highlights: fixed it");
    }
}
