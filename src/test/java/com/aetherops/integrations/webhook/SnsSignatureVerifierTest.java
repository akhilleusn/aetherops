package com.aetherops.integrations.webhook;

import com.aetherops.integrations.model.CloudWatchSnsRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 1 Step E, item 2 (finding 5). A self-signed cert + key pair is generated once via
 * the JDK's own bundled keytool (there is no public java.security API to construct/sign an
 * X509Certificate — only to parse one — so this is the standard dependency-free way to get
 * real, parseable certificate bytes for a test, without adding a crypto library to the
 * project just for this).
 */
class SnsSignatureVerifierTest {

    private static X509Certificate certificate;
    private static PrivateKey privateKey;
    private static byte[] certificateDerBytes;

    @BeforeAll
    static void generateSelfSignedCert(@TempDir Path tempDir) throws Exception {
        Path keystorePath = tempDir.resolve("sns-test.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

        Process process = new ProcessBuilder(
            keytool, "-genkeypair",
            "-alias", "sns-test",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=sns-test",
            "-validity", "3650",
            "-storetype", "PKCS12",
            "-keystore", keystorePath.toString(),
            "-storepass", "changeit",
            "-keypass", "changeit"
        ).redirectErrorStream(true).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("keytool failed to generate a test certificate, exit=" + exitCode
                + " output=" + new String(process.getInputStream().readAllBytes()));
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        certificate = (X509Certificate) keyStore.getCertificate("sns-test");
        privateKey = (PrivateKey) keyStore.getKey("sns-test", "changeit".toCharArray());
        certificateDerBytes = certificate.getEncoded();
    }

    private RestClient.Builder mockRestClientReturning(byte[] body) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient client = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.get()).thenReturn(getSpec);
        when(getSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(byte[].class)).thenReturn(body);
        return builder;
    }

    private String sign(String stringToSign) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private CloudWatchSnsRequest notification(String message, String subject) {
        CloudWatchSnsRequest r = new CloudWatchSnsRequest();
        r.setType("Notification");
        r.setMessage(message);
        r.setMessageId("msg-1");
        r.setTopicArn("arn:aws:sns:us-east-1:123456789012:test-topic");
        r.setTimestamp("2026-01-01T00:00:00.000Z");
        r.setSubject(subject);
        r.setSigningCertURL("https://sns.us-east-1.amazonaws.com/SimpleNotificationService-abc.pem");
        r.setSignatureVersion("2");
        return r;
    }

    private String stringToSignFor(CloudWatchSnsRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Message\n").append(r.getMessage()).append('\n');
        if (r.getSubject() != null) {
            sb.append("Subject\n").append(r.getSubject()).append('\n');
        }
        sb.append("MessageId\n").append(r.getMessageId()).append('\n');
        sb.append("Timestamp\n").append(r.getTimestamp()).append('\n');
        sb.append("TopicArn\n").append(r.getTopicArn()).append('\n');
        sb.append("Type\n").append(r.getType()).append('\n');
        return sb.toString();
    }

    @Test
    void validSignature_accepted() throws Exception {
        CloudWatchSnsRequest request = notification("{\"AlarmName\":\"HighLatency\"}", null);
        request.setSignature(sign(stringToSignFor(request)));

        SnsSignatureVerifier verifier = new SnsSignatureVerifier(mockRestClientReturning(certificateDerBytes));

        assertThat(verifier.verify(request)).isTrue();
    }

    @Test
    void tamperedMessage_afterSigning_rejected() throws Exception {
        CloudWatchSnsRequest request = notification("{\"AlarmName\":\"HighLatency\"}", null);
        request.setSignature(sign(stringToSignFor(request)));
        request.setMessage("{\"AlarmName\":\"AttackerInjectedAlarm\"}"); // tampered after signing

        SnsSignatureVerifier verifier = new SnsSignatureVerifier(mockRestClientReturning(certificateDerBytes));

        assertThat(verifier.verify(request)).isFalse();
    }

    @Test
    void signatureFromDifferentKeyPair_rejected() throws Exception {
        CloudWatchSnsRequest request = notification("{\"AlarmName\":\"HighLatency\"}", null);
        // Sign with a valid RSA key, but not the one whose cert gets served back.
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kpg.generateKeyPair().getPrivate());
        sig.update(stringToSignFor(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setSignature(Base64.getEncoder().encodeToString(sig.sign()));

        SnsSignatureVerifier verifier = new SnsSignatureVerifier(mockRestClientReturning(certificateDerBytes));

        assertThat(verifier.verify(request)).isFalse();
    }

    @Test
    void nonAwsSigningCertUrl_rejected_withoutEverFetchingIt() {
        CloudWatchSnsRequest request = notification("{\"AlarmName\":\"HighLatency\"}", null);
        request.setSignature("irrelevant-not-reached");
        request.setSigningCertURL("https://sns.us-east-1.amazonaws.com.evil.com/cert.pem");

        RestClient.Builder builder = mock(RestClient.Builder.class);
        SnsSignatureVerifier verifier = new SnsSignatureVerifier(builder);

        assertThat(verifier.verify(request)).isFalse();
        verifyNoInteractions(builder);
    }

    @Test
    void httpNotHttpsSigningCertUrl_rejected_withoutEverFetchingIt() {
        CloudWatchSnsRequest request = notification("{\"AlarmName\":\"HighLatency\"}", null);
        request.setSignature("irrelevant-not-reached");
        request.setSigningCertURL("http://sns.us-east-1.amazonaws.com/cert.pem");

        RestClient.Builder builder = mock(RestClient.Builder.class);
        SnsSignatureVerifier verifier = new SnsSignatureVerifier(builder);

        assertThat(verifier.verify(request)).isFalse();
        verifyNoInteractions(builder);
    }

    @Test
    void missingSignature_rejected() {
        CloudWatchSnsRequest request = notification("{\"AlarmName\":\"HighLatency\"}", null);
        request.setSignature(null);

        RestClient.Builder builder = mock(RestClient.Builder.class);
        SnsSignatureVerifier verifier = new SnsSignatureVerifier(builder);

        assertThat(verifier.verify(request)).isFalse();
        verifyNoInteractions(builder);
    }

    @Test
    void subscriptionConfirmation_validSignature_accepted() throws Exception {
        CloudWatchSnsRequest request = new CloudWatchSnsRequest();
        request.setType("SubscriptionConfirmation");
        request.setMessage("You have chosen to subscribe to the topic.");
        request.setMessageId("msg-2");
        request.setSubscribeURL("https://sns.us-east-1.amazonaws.com/confirm?token=abc");
        request.setTimestamp("2026-01-01T00:00:00.000Z");
        request.setToken("abc-token");
        request.setTopicArn("arn:aws:sns:us-east-1:123456789012:test-topic");
        request.setSigningCertURL("https://sns.us-east-1.amazonaws.com/SimpleNotificationService-abc.pem");
        request.setSignatureVersion("2");

        StringBuilder sb = new StringBuilder();
        sb.append("Message\n").append(request.getMessage()).append('\n');
        sb.append("MessageId\n").append(request.getMessageId()).append('\n');
        sb.append("SubscribeURL\n").append(request.getSubscribeURL()).append('\n');
        sb.append("Timestamp\n").append(request.getTimestamp()).append('\n');
        sb.append("Token\n").append(request.getToken()).append('\n');
        sb.append("TopicArn\n").append(request.getTopicArn()).append('\n');
        sb.append("Type\n").append(request.getType()).append('\n');
        request.setSignature(sign(sb.toString()));

        SnsSignatureVerifier verifier = new SnsSignatureVerifier(mockRestClientReturning(certificateDerBytes));

        assertThat(verifier.verify(request)).isTrue();
    }
}
