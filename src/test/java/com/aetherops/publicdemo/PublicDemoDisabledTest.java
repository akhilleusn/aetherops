package com.aetherops.publicdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No app.public-demo.tenant-id: the configuration production ships with until it's set. */
class PublicDemoDisabledTest extends AbstractPublicDemoTest {

    @Autowired PublicDemoProperties properties;

    @Test
    void withNoDemoTenantConfigured_everyDemoRouteIs404() throws Exception {
        assertThat(properties.tenantId()).isEmpty();
        seedIncident(DEMO_TENANT, "Demo");
        mockMvc.perform(get(PREFIX + "/incidents")).andExpect(status().isNotFound());
        mockMvc.perform(post(PREFIX + "/incidents")).andExpect(status().isNotFound());
    }

    @Test
    void defaultLimit_is60RequestsPerMinute() {
        assertThat(properties.requestsPerWindow()).isEqualTo(60);
        assertThat(properties.windowSeconds()).isEqualTo(60);
    }

    @Test
    void removedJwtMintingDemoRoutes_stayGone() throws Exception {
        for (String path : List.of("/api/v1/demo/auth", "/api/v1/demo/tenant")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());                          // anonymous
            mockMvc.perform(get(path).with(asTenant(DEMO_TENANT))).andExpect(status().isNotFound());  // route doesn't exist
        }
    }
}
