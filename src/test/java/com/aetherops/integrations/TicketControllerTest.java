package com.aetherops.integrations;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.integrations.controller.TicketController;
import com.aetherops.integrations.model.CreateTicketRequest;
import com.aetherops.integrations.service.JiraTicketService;
import com.aetherops.integrations.service.ServiceNowTicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketControllerTest {

    @Mock private JiraTicketService jiraTicketService;
    @Mock private ServiceNowTicketService serviceNowTicketService;

    @InjectMocks
    private TicketController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @Test
    void createJiraTicket_success_returnsTicketId() {
        CreateTicketRequest request = new CreateTicketRequest("db down", "details");
        when(jiraTicketService.createIssue(tenantId, incidentId, "db down", "details")).thenReturn(Optional.of("OPS-42"));

        ApiResponse<Map<String, String>> response = controller.createJiraTicket(tenantId, incidentId, request);

        assertThat(response.getData()).containsEntry("ticketId", "OPS-42");
    }

    @Test
    void createJiraTicket_notConfigured_throwsResourceNotFound() {
        CreateTicketRequest request = new CreateTicketRequest("db down", "details");
        when(jiraTicketService.createIssue(tenantId, incidentId, "db down", "details")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.createJiraTicket(tenantId, incidentId, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createServiceNowTicket_success_returnsTicketId() {
        CreateTicketRequest request = new CreateTicketRequest("db down", "details");
        when(serviceNowTicketService.createIncidentTicket(tenantId, incidentId, "db down", "details"))
            .thenReturn(Optional.of("sys-id-123"));

        ApiResponse<Map<String, String>> response = controller.createServiceNowTicket(tenantId, incidentId, request);

        assertThat(response.getData()).containsEntry("ticketId", "sys-id-123");
    }

    @Test
    void createServiceNowTicket_notConfigured_throwsResourceNotFound() {
        CreateTicketRequest request = new CreateTicketRequest("db down", "details");
        when(serviceNowTicketService.createIncidentTicket(tenantId, incidentId, "db down", "details"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.createServiceNowTicket(tenantId, incidentId, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
