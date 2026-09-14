package com.aetherops.integrations.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3 finding 6 (see STEP_REPORT.md): the Jira/ServiceNow API token must never appear in toString(). */
class ConfigureTicketIntegrationRequestTest {

    @Test
    void toString_redactsApiToken_keepsOtherFieldsVisible() {
        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.JIRA, "https://acme.atlassian.net", "OPS", "bot@acme.com", "super-secret-api-token");

        String s = request.toString();

        assertThat(s).contains("JIRA", "https://acme.atlassian.net", "OPS", "bot@acme.com");
        assertThat(s).doesNotContain("super-secret-api-token");
    }
}
