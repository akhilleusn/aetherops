package com.aetherops.integrations.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwsSnsHostValidatorTest {

    @Test
    void genuineSnsHost_valid() {
        assertThat(AwsSnsHostValidator.isValidSnsHost("https://sns.us-east-1.amazonaws.com/cert.pem")).isTrue();
    }

    @Test
    void genuineChinaPartitionSnsHost_valid() {
        assertThat(AwsSnsHostValidator.isValidSnsHost("https://sns.cn-north-1.amazonaws.com.cn/cert.pem")).isTrue();
    }

    @Test
    void lookalikeHost_invalid() {
        assertThat(AwsSnsHostValidator.isValidSnsHost("https://sns.us-east-1.amazonaws.com.evil.com/cert.pem")).isFalse();
    }

    @Test
    void httpNotHttps_invalid() {
        assertThat(AwsSnsHostValidator.isValidSnsHost("http://sns.us-east-1.amazonaws.com/cert.pem")).isFalse();
    }

    @Test
    void attackerControlledArbitraryHost_invalid() {
        assertThat(AwsSnsHostValidator.isValidSnsHost("https://attacker.example.com/fake-cert.pem")).isFalse();
    }

    @Test
    void nullOrBlank_invalid() {
        assertThat(AwsSnsHostValidator.isValidSnsHost(null)).isFalse();
        assertThat(AwsSnsHostValidator.isValidSnsHost("")).isFalse();
    }

    @Test
    void malformedUrl_invalid() {
        assertThat(AwsSnsHostValidator.isValidSnsHost("not a url at all")).isFalse();
    }
}
