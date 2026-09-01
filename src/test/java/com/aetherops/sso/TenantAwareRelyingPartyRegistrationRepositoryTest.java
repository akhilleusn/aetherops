package com.aetherops.sso;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Generates a real self-signed X.509 certificate rather than a hand-typed PEM fixture. */
@ExtendWith(MockitoExtension.class)
class TenantAwareRelyingPartyRegistrationRepositoryTest {

    private static String testCertificatePem;

    @Mock private TenantSsoConfigRepository ssoConfigRepository;

    @InjectMocks
    private TenantAwareRelyingPartyRegistrationRepository registrationRepository;

    @BeforeAll
    static void generateTestCertificate() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Name subject = new X500Name("CN=test-idp.example.com");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 86_400_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 86_400_000L);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded());
        testCertificatePem = "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----";
    }

    private TenantSsoConfig config(UUID tenantId, boolean enabled) {
        return TenantSsoConfig.builder()
            .tenantId(tenantId).enabled(enabled)
            .idpEntityId("https://idp.example.com/entity")
            .idpSsoUrl("https://idp.example.com/sso")
            .idpCertificate(testCertificatePem)
            .build();
    }

    @Test
    void findByRegistrationId_enabledTenant_buildsRegistration() {
        UUID tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(registrationRepository, "baseUrl", "https://app.aetherops.dev");
        ReflectionTestUtils.setField(registrationRepository, "spEntityId", "aetherops");
        when(ssoConfigRepository.findByTenantIdAndEnabledTrue(tenantId)).thenReturn(Optional.of(config(tenantId, true)));

        RelyingPartyRegistration registration = registrationRepository.findByRegistrationId(tenantId.toString());

        assertThat(registration).isNotNull();
        assertThat(registration.getRegistrationId()).isEqualTo(tenantId.toString());
        assertThat(registration.getEntityId()).isEqualTo("aetherops");
        assertThat(registration.getAssertionConsumerServiceLocation())
            .isEqualTo("https://app.aetherops.dev/login/saml2/sso/" + tenantId);
        assertThat(registration.getAssertingPartyMetadata().getEntityId())
            .isEqualTo("https://idp.example.com/entity");
        assertThat(registration.getAssertingPartyMetadata().getSingleSignOnServiceLocation())
            .isEqualTo("https://idp.example.com/sso");
    }

    @Test
    void findByRegistrationId_disabledTenant_returnsNull() {
        UUID tenantId = UUID.randomUUID();
        when(ssoConfigRepository.findByTenantIdAndEnabledTrue(tenantId)).thenReturn(Optional.empty());

        assertThat(registrationRepository.findByRegistrationId(tenantId.toString())).isNull();
    }

    @Test
    void findByRegistrationId_unknownTenant_returnsNull() {
        UUID tenantId = UUID.randomUUID();
        when(ssoConfigRepository.findByTenantIdAndEnabledTrue(tenantId)).thenReturn(Optional.empty());

        assertThat(registrationRepository.findByRegistrationId(tenantId.toString())).isNull();
    }

    @Test
    void findByRegistrationId_malformedRegistrationId_returnsNullWithoutThrowing() {
        assertThat(registrationRepository.findByRegistrationId("not-a-uuid")).isNull();
    }

    @Test
    void findByRegistrationId_registrationDoesNotRequireSignedAuthnRequests() {
        UUID tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(registrationRepository, "baseUrl", "https://app.aetherops.dev");
        ReflectionTestUtils.setField(registrationRepository, "spEntityId", "aetherops");
        when(ssoConfigRepository.findByTenantIdAndEnabledTrue(tenantId)).thenReturn(Optional.of(config(tenantId, true)));

        RelyingPartyRegistration registration = registrationRepository.findByRegistrationId(tenantId.toString());

        assertThat(registration.getAssertingPartyMetadata().getWantAuthnRequestsSigned()).isFalse();
    }

    @Test
    void findByRegistrationId_differentTenants_produceIndependentRegistrations() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        ReflectionTestUtils.setField(registrationRepository, "baseUrl", "https://app.aetherops.dev");
        ReflectionTestUtils.setField(registrationRepository, "spEntityId", "aetherops");
        when(ssoConfigRepository.findByTenantIdAndEnabledTrue(tenantA)).thenReturn(Optional.of(config(tenantA, true)));
        when(ssoConfigRepository.findByTenantIdAndEnabledTrue(tenantB)).thenReturn(Optional.empty());

        assertThat(registrationRepository.findByRegistrationId(tenantA.toString())).isNotNull();
        assertThat(registrationRepository.findByRegistrationId(tenantB.toString())).isNull();
    }
}
