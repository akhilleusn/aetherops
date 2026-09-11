package com.aetherops.slack;

import com.aetherops.oncall.OnCallService;
import com.aetherops.oncall.OnCallService.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Step G1 (see STEP_REPORT.md): the manual {@code /page} Slack command used to fire-and-forget
 * {@code OnCallService.pageOnCall(...)} the same way the automatic paths did — the person who
 * typed the command got an immediate "Paging on-call engineer..." acknowledgment and then never
 * found out whether it actually worked. {@code OnCallService.pageOnCall} is now synchronous and
 * returns a result, but {@code SlackCommandController}'s endpoint still has to answer Slack
 * within its 3-second slash-command window — so this service owns paging asynchronously on that
 * command's behalf, then reports the real outcome back via Slack's {@code response_url} (a
 * delayed-response webhook Slack hands back in every slash-command payload, postable up to 30
 * minutes later) once it's known.
 *
 * <p>Deliberately a separate bean from {@link SlackCommandController}: {@code @Async} only takes
 * effect through Spring's proxy, which a same-class self-invocation bypasses entirely — exactly
 * the kind of silent no-op this whole batch exists to close, so it isn't repeated here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlackPageResponseService {

    private final OnCallService onCallService;
    private final RestClient.Builder restClientBuilder;

    @Async
    public void pageAndRespond(UUID tenantId, String serviceId, String title, String responseUrl) {
        PageResult result = onCallService.pageOnCall(tenantId, serviceId, title);
        String text = result.succeeded()
            ? "✅ Paged on-call engineer for `" + serviceId + "`."
            : "❌ Failed to page on-call engineer for `" + serviceId + "`: " + describe(result);

        try {
            restClientBuilder.build().post()
                .uri(responseUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("response_type", "ephemeral", "text", text))
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to post /page outcome back to Slack response_url", e);
        }
    }

    private String describe(PageResult result) {
        return switch (result.outcome()) {
            case NOT_CONFIGURED -> "SMS paging isn't configured for this deployment.";
            case NO_ROTATION -> "no on-call rotation is configured for this service.";
            case NO_ENGINEER_AVAILABLE -> "no on-call engineer is currently available.";
            case PROVIDER_ERROR -> "the paging provider rejected the request"
                + (result.detail() != null ? " (" + result.detail() + ")" : "") + ".";
            case ACCEPTED -> "";
        };
    }
}
