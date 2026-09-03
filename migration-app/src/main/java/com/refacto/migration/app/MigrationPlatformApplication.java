package com.refacto.migration.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.refacto.migration")
public class MigrationPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(MigrationPlatformApplication.class, args);
    }
}
