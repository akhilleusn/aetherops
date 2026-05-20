package com.aetherops.sample.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/simulate")
public class FailureController {

    @GetMapping("/db-timeout")
    public ResponseEntity<Map<String, String>> dbTimeout() {
        return ResponseEntity.status(500).body(Map.of(
            "error",   "Database connection pool exhausted after 30000ms: HikariPool-1 connection acquisition timeout",
            "service", "payment-svc",
            "code",    "DB_TIMEOUT"
        ));
    }

    @GetMapping("/slow-response")
    public ResponseEntity<Map<String, String>> slowResponse() throws InterruptedException {
        Thread.sleep(200);
        return ResponseEntity.status(504).body(Map.of(
            "error",   "Payment request exceeded 15s SLA — gateway timeout",
            "service", "payment-svc",
            "code",    "GATEWAY_TIMEOUT"
        ));
    }

    @GetMapping("/error")
    public ResponseEntity<Map<String, String>> applicationError() {
        return ResponseEntity.status(500).body(Map.of(
            "error",   "NullPointerException in PaymentProcessor.charge() at line 247",
            "service", "payment-svc",
            "code",    "INTERNAL_ERROR"
        ));
    }
}
