package com.aetherops.demo;

import com.aetherops.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for Phase 1 remediation item 1: DemoController and its
 * unauthenticated JWT-minting endpoint must no longer exist at all, in any profile.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoRoutesRemovedTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void demoAuthRoute_doesNotResolve() throws Exception {
        mockMvc.perform(get("/api/v1/demo/auth"))
            .andExpect(status().isNotFound());
    }

    @Test
    void demoTenantRoute_doesNotResolve() throws Exception {
        mockMvc.perform(get("/api/v1/demo/tenant"))
            .andExpect(status().isNotFound());
    }
}
