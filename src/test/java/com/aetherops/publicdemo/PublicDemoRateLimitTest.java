package com.aetherops.publicdemo;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with the default trusted-proxy-count of 1: the LAST X-Forwarded-For entry is the client. */
@TestPropertySource(properties = {
    "app.public-demo.tenant-id=d0000000-0000-4000-8000-000000000001",
    "app.public-demo.requests-per-window=3",
    "app.public-demo.window-seconds=60"
})
class PublicDemoRateLimitTest extends AbstractPublicDemoTest {

    private static final String CLIENT_A = "198.51.100.7";
    private static final String CLIENT_B = "198.51.100.8";

    @Test
    void perIpLimit_blocksTheFourthRequest_andCannotBeResetByForgingOrChangingPath() throws Exception {
        UUID incident = seedIncident(DEMO_TENANT, "Demo").incidentId();
        for (int i = 0; i < 3; i++) {
            demoGet("/incidents", CLIENT_A).andExpect(status().isOk());
        }
        demoGet("/incidents", CLIENT_A)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"));
        demoGet("/incidents", "1.2.3.4, " + CLIENT_A).andExpect(status().isTooManyRequests());   // forged left entry
        demoGet("/incidents/" + incident, CLIENT_A).andExpect(status().isTooManyRequests());     // other path, same bucket
        demoGet("/incidents", CLIENT_B).andExpect(status().isOk());                             // other client unaffected
    }

    @Test
    void rejectedRequests_doNotConsumeQuota() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(PREFIX + "/incidents").header("X-Forwarded-For", CLIENT_A))
                .andExpect(status().isMethodNotAllowed());
            mockMvc.perform(get(PREFIX + "/incidents").header("X-Forwarded-For", CLIENT_A).header("Authorization", "Bearer x"))
                .andExpect(status().isBadRequest());
        }
        demoGet("/incidents", CLIENT_A).andExpect(status().isOk());
    }

    private ResultActions demoGet(String path, String xForwardedFor) throws Exception {
        return mockMvc.perform(get(PREFIX + path).header("X-Forwarded-For", xForwardedFor));
    }
}
