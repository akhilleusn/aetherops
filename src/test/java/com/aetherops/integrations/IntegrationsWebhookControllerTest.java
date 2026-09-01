package com.aetherops.integrations;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.integrations.controller.IntegrationsWebhookController;
import com.aetherops.integrations.model.*;
import com.aetherops.monitoring.service.MonitoringAlertIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntegrationsWebhookControllerTest {

    @Mock private ApiKeyService apiKeyService;
    @Mock private MonitoringAlertIngestionService ingestionService;
    @Mock private RestClient.Builder restClientBuilder;

    private IntegrationsWebhookController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private static final String API_KEY = "ak_test-key";

    @BeforeEach
    void setUp() {
        controller = new IntegrationsWebhookController(apiKeyService, ingestionService, restClientBuilder);
        lenient().when(apiKeyService.resolve(API_KEY)).thenReturn(
            ApiKey.builder().id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).active(true).build());
    }

    private MonitoringAlertIngestionService.AlertSignal captureSignal() {
        ArgumentCaptor<MonitoringAlertIngestionService.AlertSignal> captor =
            ArgumentCaptor.forClass(MonitoringAlertIngestionService.AlertSignal.class);
        verify(ingestionService).ingest(captor.capture());
        return captor.getValue();
    }

    // --- Grafana ---

    @Test
    void grafana_firingCriticalAlert_mapsToCriticalAndFiring() {
        GrafanaWebhookRequest request = new GrafanaWebhookRequest();
        request.setStatus("firing");
        GrafanaWebhookRequest.Alert alert = new GrafanaWebhookRequest.Alert();
        alert.setStatus("firing");
        alert.setLabels(Map.of("alertname", "HighCpu", "service", "api", "severity", "critical"));
        alert.setAnnotations(Map.of("summary", "CPU too high"));
        request.setAlerts(List.of(alert));

        controller.grafana(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.tenantId()).isEqualTo(tenantId);
        assertThat(signal.serviceId()).isEqualTo("api");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void grafana_resolvedAlert_notFiring() {
        GrafanaWebhookRequest request = new GrafanaWebhookRequest();
        request.setStatus("resolved");
        GrafanaWebhookRequest.Alert alert = new GrafanaWebhookRequest.Alert();
        alert.setStatus("resolved");
        alert.setLabels(Map.of("alertname", "HighCpu"));
        request.setAlerts(List.of(alert));

        controller.grafana(request, API_KEY);

        assertThat(captureSignal().firing()).isFalse();
    }

    @Test
    void grafana_noAlertEntries_doesNotIngest() {
        GrafanaWebhookRequest request = new GrafanaWebhookRequest();
        request.setStatus("firing");
        request.setAlerts(List.of());

        controller.grafana(request, API_KEY);

        verifyNoInteractions(ingestionService);
    }

    // --- New Relic ---

    @Test
    void newRelic_activatedCritical_firesAsCreated() {
        NewRelicWebhookRequest request = new NewRelicWebhookRequest();
        request.setIssueId("issue-1");
        request.setTitle("DB errors");
        request.setPriority("CRITICAL");
        request.setState("ACTIVATED");
        NewRelicWebhookRequest.EntitiesData entitiesData = new NewRelicWebhookRequest.EntitiesData();
        NewRelicWebhookRequest.Entity entity = new NewRelicWebhookRequest.Entity();
        entity.setName("db-service");
        entitiesData.setEntities(List.of(entity));
        request.setEntitiesData(entitiesData);

        controller.newRelic(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("db-service");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void newRelic_closedState_notFiring() {
        NewRelicWebhookRequest request = new NewRelicWebhookRequest();
        request.setIssueId("issue-2");
        request.setTitle("Resolved");
        request.setPriority("LOW");
        request.setState("CLOSED");

        controller.newRelic(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.firing()).isFalse();
        assertThat(signal.serviceId()).isEqualTo("unknown-service");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.LOW);
    }

    // --- Splunk ---

    @Test
    void splunk_severityOne_mapsToCritical() {
        SplunkWebhookRequest request = new SplunkWebhookRequest();
        request.setSearch_name("Too many 500s");
        request.setSeverity("1");

        controller.splunk(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void splunk_severityFive_mapsToLow() {
        SplunkWebhookRequest request = new SplunkWebhookRequest();
        request.setSearch_name("Minor anomaly");
        request.setSeverity("5");

        controller.splunk(request, API_KEY);

        assertThat(captureSignal().severity()).isEqualTo(IncidentSeverity.LOW);
    }

    // --- Sentry ---

    @Test
    void sentry_fatalLevelCreated_mapsToCriticalAndFiring() {
        SentryWebhookRequest request = new SentryWebhookRequest();
        request.setAction("created");
        SentryWebhookRequest.IssueData data = new SentryWebhookRequest.IssueData();
        SentryWebhookRequest.Issue issue = new SentryWebhookRequest.Issue();
        issue.setTitle("NPE in checkout");
        issue.setLevel("fatal");
        issue.setProject("checkout-service");
        data.setIssue(issue);
        request.setData(data);

        controller.sentry(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("checkout-service");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void sentry_resolvedAction_notFiring() {
        SentryWebhookRequest request = new SentryWebhookRequest();
        request.setAction("resolved");
        SentryWebhookRequest.IssueData data = new SentryWebhookRequest.IssueData();
        SentryWebhookRequest.Issue issue = new SentryWebhookRequest.Issue();
        issue.setTitle("Fixed");
        issue.setLevel("warning");
        data.setIssue(issue);
        request.setData(data);

        controller.sentry(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.firing()).isFalse();
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.MEDIUM);
    }

    // --- Rollbar ---

    @Test
    void rollbar_criticalNewItem_firesAsCritical() {
        RollbarWebhookRequest request = new RollbarWebhookRequest();
        request.setEvent_name("new_item");
        RollbarWebhookRequest.ItemData data = new RollbarWebhookRequest.ItemData();
        RollbarWebhookRequest.Item item = new RollbarWebhookRequest.Item();
        item.setTitle("Crash on startup");
        item.setLevel("critical");
        RollbarWebhookRequest.Project project = new RollbarWebhookRequest.Project();
        project.setName("mobile-backend");
        item.setProject(project);
        data.setItem(item);
        request.setData(data);

        controller.rollbar(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("mobile-backend");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void rollbar_resolvedItem_notFiring() {
        RollbarWebhookRequest request = new RollbarWebhookRequest();
        request.setEvent_name("resolved_item");
        RollbarWebhookRequest.ItemData data = new RollbarWebhookRequest.ItemData();
        RollbarWebhookRequest.Item item = new RollbarWebhookRequest.Item();
        item.setTitle("Fixed crash");
        item.setLevel("error");
        data.setItem(item);
        request.setData(data);

        controller.rollbar(request, API_KEY);

        assertThat(captureSignal().firing()).isFalse();
    }

    // --- Sumo Logic ---

    @Test
    void sumoLogic_passesThroughFieldsDirectly() {
        SumoLogicWebhookRequest request = new SumoLogicWebhookRequest();
        request.setServiceId("search-svc");
        request.setAlertName("Error rate spike");
        request.setDescription("5xx rate above threshold");
        request.setSeverity("HIGH");
        request.setFiring(true);

        controller.sumoLogic(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("search-svc");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void sumoLogic_unrecognizedSeverity_defaultsToMedium() {
        SumoLogicWebhookRequest request = new SumoLogicWebhookRequest();
        request.setServiceId("svc");
        request.setAlertName("alert");
        request.setSeverity("bogus");
        request.setFiring(true);

        controller.sumoLogic(request, API_KEY);

        assertThat(captureSignal().severity()).isEqualTo(IncidentSeverity.MEDIUM);
    }

    // --- Opsgenie ---

    @Test
    void opsgenie_createActionWithP1Priority_mapsToCriticalAndFiring() {
        OpsgenieWebhookRequest request = new OpsgenieWebhookRequest();
        request.setAction("Create");
        OpsgenieWebhookRequest.Alert alert = new OpsgenieWebhookRequest.Alert();
        alert.setMessage("Payment gateway down");
        alert.setSource("payments");
        alert.setPriority("P1");
        request.setAlert(alert);

        controller.opsgenie(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("payments");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void opsgenie_closeAction_notFiring() {
        OpsgenieWebhookRequest request = new OpsgenieWebhookRequest();
        request.setAction("Close");
        OpsgenieWebhookRequest.Alert alert = new OpsgenieWebhookRequest.Alert();
        alert.setMessage("Resolved");
        alert.setPriority("P4");
        request.setAlert(alert);

        controller.opsgenie(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.firing()).isFalse();
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.MEDIUM);
    }

    // --- VictorOps ---

    @Test
    void victorOps_criticalMessage_firesAsCritical() {
        VictorOpsWebhookRequest request = new VictorOpsWebhookRequest();
        request.setMessage_type("CRITICAL");
        request.setEntity_id("host-1");
        request.setEntity_display_name("web-01");
        request.setState_message("Disk full");

        controller.victorOps(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("web-01");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void victorOps_recoveryMessage_notFiring() {
        VictorOpsWebhookRequest request = new VictorOpsWebhookRequest();
        request.setMessage_type("RECOVERY");
        request.setEntity_id("host-1");

        controller.victorOps(request, API_KEY);

        assertThat(captureSignal().firing()).isFalse();
    }

    // --- PagerDuty ---

    @Test
    void pagerDuty_triggeredWithP1_mapsToCriticalAndFiring() {
        PagerDutyWebhookRequest request = new PagerDutyWebhookRequest();
        PagerDutyWebhookRequest.Event event = new PagerDutyWebhookRequest.Event();
        event.setEvent_type("incident.triggered");
        PagerDutyWebhookRequest.EventData data = new PagerDutyWebhookRequest.EventData();
        data.setTitle("Site down");
        PagerDutyWebhookRequest.Priority priority = new PagerDutyWebhookRequest.Priority();
        priority.setSummary("P1");
        data.setPriority(priority);
        PagerDutyWebhookRequest.ServiceRef serviceRef = new PagerDutyWebhookRequest.ServiceRef();
        serviceRef.setSummary("web");
        data.setService(serviceRef);
        event.setData(data);
        request.setEvent(event);

        controller.pagerDuty(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("web");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void pagerDuty_resolvedEvent_notFiring() {
        PagerDutyWebhookRequest request = new PagerDutyWebhookRequest();
        PagerDutyWebhookRequest.Event event = new PagerDutyWebhookRequest.Event();
        event.setEvent_type("incident.resolved");
        PagerDutyWebhookRequest.EventData data = new PagerDutyWebhookRequest.EventData();
        data.setTitle("Site back up");
        event.setData(data);
        request.setEvent(event);

        controller.pagerDuty(request, API_KEY);

        assertThat(captureSignal().firing()).isFalse();
    }

    // --- CloudWatch ---

    @Test
    void cloudWatch_alarmNotification_firesAsHigh() throws Exception {
        String innerMessage = "{\"AlarmName\":\"HighLatency\",\"NewStateValue\":\"ALARM\",\"NewStateReason\":\"Threshold crossed\"}";
        CloudWatchSnsRequest request = new CloudWatchSnsRequest();
        request.setType("Notification");
        request.setMessage(innerMessage);

        controller.cloudWatch(request, API_KEY);

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("HighLatency");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void cloudWatch_okStateNotification_notFiring() {
        String innerMessage = "{\"AlarmName\":\"HighLatency\",\"NewStateValue\":\"OK\"}";
        CloudWatchSnsRequest request = new CloudWatchSnsRequest();
        request.setType("Notification");
        request.setMessage(innerMessage);

        controller.cloudWatch(request, API_KEY);

        assertThat(captureSignal().firing()).isFalse();
    }

    @Test
    void cloudWatch_subscriptionConfirmation_confirmsAndDoesNotIngest() {
        RestClient mockRestClient = mock(RestClient.class);
        when(restClientBuilder.build()).thenReturn(mockRestClient);
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class, org.mockito.Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(mockRestClient.get()).thenReturn(getSpec);
        when(getSpec.retrieve()).thenReturn(responseSpec);

        CloudWatchSnsRequest request = new CloudWatchSnsRequest();
        request.setType("SubscriptionConfirmation");
        request.setSubscribeURL("https://sns.us-east-1.amazonaws.com/confirm?token=abc");

        controller.cloudWatch(request, API_KEY);

        verify(mockRestClient).get();
        verifyNoInteractions(ingestionService);
    }

    @Test
    void cloudWatch_subscriptionConfirmation_nonAwsHost_refusesToFetch() {
        CloudWatchSnsRequest request = new CloudWatchSnsRequest();
        request.setType("SubscriptionConfirmation");
        request.setSubscribeURL("http://169.254.169.254/latest/meta-data/iam/security-credentials/some-role");

        controller.cloudWatch(request, API_KEY);

        verifyNoInteractions(restClientBuilder);
        verifyNoInteractions(ingestionService);
    }

    @Test
    void cloudWatch_subscriptionConfirmation_httpNotHttps_refusesToFetch() {
        CloudWatchSnsRequest request = new CloudWatchSnsRequest();
        request.setType("SubscriptionConfirmation");
        request.setSubscribeURL("http://sns.us-east-1.amazonaws.com/confirm?token=abc");

        controller.cloudWatch(request, API_KEY);

        verifyNoInteractions(restClientBuilder);
    }

    @Test
    void cloudWatch_subscriptionConfirmation_lookalikeHost_refusesToFetch() {
        CloudWatchSnsRequest request = new CloudWatchSnsRequest();
        request.setType("SubscriptionConfirmation");
        request.setSubscribeURL("https://sns.us-east-1.amazonaws.com.evil.com/confirm?token=abc");

        controller.cloudWatch(request, API_KEY);

        verifyNoInteractions(restClientBuilder);
    }

    // --- GitHub ---

    @Test
    void gitHub_deploymentFailure_createsHighSeverityIncident() {
        GitHubDeploymentStatusRequest request = new GitHubDeploymentStatusRequest();
        GitHubDeploymentStatusRequest.DeploymentStatus status = new GitHubDeploymentStatusRequest.DeploymentStatus();
        status.setState("failure");
        request.setDeployment_status(status);
        GitHubDeploymentStatusRequest.Deployment deployment = new GitHubDeploymentStatusRequest.Deployment();
        deployment.setEnvironment("production");
        deployment.setSha("abc123");
        request.setDeployment(deployment);
        GitHubDeploymentStatusRequest.Repository repository = new GitHubDeploymentStatusRequest.Repository();
        repository.setFull_name("acme/api");
        request.setRepository(repository);

        controller.gitHub(request, API_KEY, "deployment_status");

        var signal = captureSignal();
        assertThat(signal.serviceId()).isEqualTo("acme/api");
        assertThat(signal.severity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(signal.firing()).isTrue();
    }

    @Test
    void gitHub_deploymentSuccess_resolvesWithSameAlertName() {
        GitHubDeploymentStatusRequest request = new GitHubDeploymentStatusRequest();
        GitHubDeploymentStatusRequest.DeploymentStatus status = new GitHubDeploymentStatusRequest.DeploymentStatus();
        status.setState("success");
        request.setDeployment_status(status);
        GitHubDeploymentStatusRequest.Deployment deployment = new GitHubDeploymentStatusRequest.Deployment();
        deployment.setEnvironment("production");
        request.setDeployment(deployment);
        GitHubDeploymentStatusRequest.Repository repository = new GitHubDeploymentStatusRequest.Repository();
        repository.setFull_name("acme/api");
        request.setRepository(repository);

        controller.gitHub(request, API_KEY, "deployment_status");

        var signal = captureSignal();
        assertThat(signal.firing()).isFalse();
        assertThat(signal.alertName()).isEqualTo("deploy:acme/api:production");
    }

    @Test
    void gitHub_pendingState_doesNotIngest() {
        GitHubDeploymentStatusRequest request = new GitHubDeploymentStatusRequest();
        GitHubDeploymentStatusRequest.DeploymentStatus status = new GitHubDeploymentStatusRequest.DeploymentStatus();
        status.setState("pending");
        request.setDeployment_status(status);

        controller.gitHub(request, API_KEY, "deployment_status");

        verifyNoInteractions(ingestionService);
    }

    @Test
    void gitHub_wrongEventType_ignoredWithoutError() {
        GitHubDeploymentStatusRequest request = new GitHubDeploymentStatusRequest();

        controller.gitHub(request, API_KEY, "push");

        verifyNoInteractions(ingestionService);
    }
}
