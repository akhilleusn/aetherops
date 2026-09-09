package com.aetherops.integrations.webhook;

import com.aetherops.integrations.model.CloudWatchSnsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Phase 1 Step E, item 2 (finding 5): verifies AWS SNS message signatures for the
 * cloudwatch endpoint, per docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-
 * message-verify-message-signature.html. Unlike GitHub/PagerDuty/Grafana this is not an
 * opt-in per-tenant secret — AWS signs every SNS message unconditionally, so verification
 * here is mandatory, not conditional on any tenant configuration.
 *
 * The classic mistake this exists to close: SigningCertURL is attacker-influenced input
 * (it's a field in the POSTed JSON). Fetching whatever URL it names and trusting that
 * certificate would let anyone self-sign a "valid" SNS message with their own key. AwsSnsHostValidator
 * rejects any SigningCertURL that isn't a genuine sns.{region}.amazonaws.com(.cn) host
 * BEFORE it is ever fetched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SnsSignatureVerifier {

    private final RestClient.Builder restClientBuilder;

    public boolean verify(CloudWatchSnsRequest message) {
        if (message.getSignature() == null || message.getSigningCertURL() == null
            || message.getMessageId() == null || message.getType() == null) {
            log.warn("Rejected SNS message: missing Signature/SigningCertURL/MessageId/Type");
            return false;
        }
        if (!AwsSnsHostValidator.isValidSnsHost(message.getSigningCertURL())) {
            log.warn("Refusing to verify SNS signature — SigningCertURL is not a genuine AWS SNS host: {}",
                message.getSigningCertURL());
            return false;
        }

        try {
            byte[] certBytes = restClientBuilder.build().get()
                .uri(message.getSigningCertURL())
                .retrieve()
                .body(byte[].class);
            if (certBytes == null) {
                return false;
            }

            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(
                new ByteArrayInputStream(certBytes));

            String stringToSign = buildStringToSign(message);
            byte[] signatureBytes = Base64.getDecoder().decode(message.getSignature());

            String algorithm = "2".equals(message.getSignatureVersion()) ? "SHA256withRSA" : "SHA1withRSA";
            Signature signature = Signature.getInstance(algorithm);
            signature.initVerify(certificate.getPublicKey());
            signature.update(stringToSign.getBytes(StandardCharsets.UTF_8));
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            log.warn("Failed to verify SNS message signature", e);
            return false;
        }
    }

    /** Field set and order are fixed by AWS and differ by message Type — see the class javadoc link. */
    private String buildStringToSign(CloudWatchSnsRequest m) {
        StringBuilder sb = new StringBuilder();
        boolean isConfirmation = "SubscriptionConfirmation".equals(m.getType())
            || "UnsubscribeConfirmation".equals(m.getType());

        if (isConfirmation) {
            appendField(sb, "Message", m.getMessage());
            appendField(sb, "MessageId", m.getMessageId());
            appendField(sb, "SubscribeURL", m.getSubscribeURL());
            appendField(sb, "Timestamp", m.getTimestamp());
            appendField(sb, "Token", m.getToken());
            appendField(sb, "TopicArn", m.getTopicArn());
            appendField(sb, "Type", m.getType());
        } else {
            appendField(sb, "Message", m.getMessage());
            if (m.getSubject() != null) {
                appendField(sb, "Subject", m.getSubject());
            }
            appendField(sb, "MessageId", m.getMessageId());
            appendField(sb, "Timestamp", m.getTimestamp());
            appendField(sb, "TopicArn", m.getTopicArn());
            appendField(sb, "Type", m.getType());
        }
        return sb.toString();
    }

    private void appendField(StringBuilder sb, String name, String value) {
        sb.append(name).append('\n').append(value != null ? value : "").append('\n');
    }
}
