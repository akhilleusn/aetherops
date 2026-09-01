package com.aetherops.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves each tenant's SAML relying-party config on demand from the database rather than
 * from static application.yml entries — each enterprise tenant brings its own IdP. The
 * registrationId IS the tenant's UUID, embedded in the ACS URL as /login/saml2/sso/{tenantId}.
 */
@Component
@RequiredArgsConstructor
public class TenantAwareRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository {

    private final TenantSsoConfigRepository ssoConfigRepository;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.sso.sp-entity-id:aetherops}")
    private String spEntityId;

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        UUID tenantId;
        try {
            tenantId = UUID.fromString(registrationId);
        } catch (IllegalArgumentException e) {
            return null;
        }

        Optional<TenantSsoConfig> configOpt = ssoConfigRepository.findByTenantIdAndEnabledTrue(tenantId);
        if (configOpt.isEmpty()) {
            return null;
        }
        TenantSsoConfig config = configOpt.get();

        Saml2X509Credential verificationCredential = Saml2X509Credential.verification(parseCertificate(config.getIdpCertificate()));

        return RelyingPartyRegistration.withRegistrationId(registrationId)
            .entityId(spEntityId)
            .assertionConsumerServiceLocation(baseUrl + "/login/saml2/sso/" + registrationId)
            .assertingPartyMetadata(party -> party
                .entityId(config.getIdpEntityId())
                .singleSignOnServiceLocation(config.getIdpSsoUrl())
                .wantAuthnRequestsSigned(false)
                .verificationX509Credentials(c -> c.add(verificationCredential)))
            .build();
    }

    private X509Certificate parseCertificate(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse IdP certificate", e);
        }
    }
}
