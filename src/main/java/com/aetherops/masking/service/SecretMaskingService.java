package com.aetherops.masking.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class SecretMaskingService {

    private static final String MASK = "[REDACTED]";

    private record MaskRule(Pattern pattern, String replacement) {}

    private static final List<MaskRule> RULES = List.of(
        // Authorization: Bearer <token>
        new MaskRule(
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9\\-._~+/]+=*", Pattern.CASE_INSENSITIVE),
            "$1" + MASK
        ),
        // Authorization: Basic <credentials>
        new MaskRule(
            Pattern.compile("(Basic\\s+)[A-Za-z0-9+/]+=*", Pattern.CASE_INSENSITIVE),
            "$1" + MASK
        ),
        // JSON key-value pairs: "password": "secret-value"
        new MaskRule(
            Pattern.compile(
                "(?i)(\"(?:password|passwd|secret|api_key|apikey|token|access_token|private_key|client_secret|auth_token)\"\\s*:\\s*\")([^\"]+)(\")"
            ),
            "$1" + MASK + "$3"
        ),
        // key=value or key: value in log lines
        new MaskRule(
            Pattern.compile(
                "(?i)((?:password|passwd|secret|api_key|apikey|token|access_token)\\s*[=:]\\s*)([^\\s&,;\"'\\n]+)"
            ),
            "$1" + MASK
        ),
        // JDBC URLs: jdbc:driver://user:password@host/db
        new MaskRule(
            Pattern.compile("(jdbc:[a-z0-9+]+://)[^:]+:[^@]+(@)", Pattern.CASE_INSENSITIVE),
            "$1" + MASK + ":" + MASK + "$2"
        )
    );

    public String mask(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String result = input;
        for (MaskRule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }
}
