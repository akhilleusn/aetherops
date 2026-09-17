package com.aetherops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Step H2 (see STEP_REPORT.md): no bean anywhere in this codebase previously configured a
 * connect/read timeout on the autoconfigured {@code RestClient.Builder}/{@code
 * RestTemplateBuilder} every outbound HTTP call in this app is built from -- Spring Boot's
 * default is an unbounded {@code JdkClientHttpRequestFactory} (no Apache HttpClient5/OkHttp/
 * Jetty client is on this project's classpath, confirmed by dependency scan, so {@code
 * JdkClientHttpRequestFactory} wrapping {@code java.net.http.HttpClient} is what every one of
 * these beans resolves to). A hanging third party could block the calling thread indefinitely.
 *
 * <p>{@link RestClientCustomizer}/{@link RestTemplateCustomizer} beans are applied automatically
 * by Spring Boot's autoconfiguration to every {@code RestClient.Builder}/{@code
 * RestTemplateBuilder} it hands out -- including the fresh-instance-per-injection-point ones
 * {@code SsrfGuard}'s javadoc already relies on -- so this is a genuinely global default with no
 * per-call-site wiring needed. It covers Slack, calendar, push (Expo), the Anthropic health
 * check, and the AWS SNS certificate/subscription-confirmation calls.
 *
 * <p>Jira, ServiceNow, and webhook delivery do NOT rely on this default: {@code
 * SsrfGuard.disableRedirects(builder, connectTimeout, readTimeout)} replaces the request factory
 * entirely (it has to, to disable redirects), so those three call sites pass their own,
 * separately-configured timeouts directly -- see {@code JiraApiClient}, {@code
 * ServiceNowApiClient}, {@code WebhookService}. Twilio does not go through Spring's
 * {@code RestClient} at all -- see {@code TwilioConfig}.
 */
@Configuration
public class RestClientTimeoutConfig {

    @Bean
    public RestClientCustomizer defaultRestClientTimeoutCustomizer(
            @Value("${app.http-client.default-connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.http-client.default-read-timeout-ms}") long readTimeoutMs) {
        Duration connectTimeout = Duration.ofMillis(connectTimeoutMs);
        Duration readTimeout = Duration.ofMillis(readTimeoutMs);
        return builder -> builder.requestFactory(buildFactory(connectTimeout, readTimeout));
    }

    @Bean
    public RestTemplateCustomizer defaultRestTemplateTimeoutCustomizer(
            @Value("${app.http-client.default-connect-timeout-ms}") long connectTimeoutMs,
            @Value("${app.http-client.default-read-timeout-ms}") long readTimeoutMs) {
        Duration connectTimeout = Duration.ofMillis(connectTimeoutMs);
        Duration readTimeout = Duration.ofMillis(readTimeoutMs);
        return restTemplate -> restTemplate.setRequestFactory(buildFactory(connectTimeout, readTimeout));
    }

    private static JdkClientHttpRequestFactory buildFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
