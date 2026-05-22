package com.aetherops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AetherOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AetherOpsApplication.class, args);
    }
}
