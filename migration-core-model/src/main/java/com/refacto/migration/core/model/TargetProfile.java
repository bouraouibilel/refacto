package com.refacto.migration.core.model;

import java.util.Map;

public record TargetProfile(
        String id,
        String name,
        String description,
        String targetJavaVersion,
        String targetSpringBootVersion,
        String targetSpringBatchVersion,
        Map<String, String> targets
) {
    public static TargetProfile defaultJava17Profile() {
        return java17Profile();
    }

    public static TargetProfile java17Profile() {
        return new TargetProfile(
                "java-17-spring-6",
                "Java 17 LTS & Spring Boot 3 (Spring 6)",
                "Migration vers Java 17 LTS, Spring Boot 3.3.x, Spring Batch 5.1.x, Hibernate 6 et Jakarta EE 10",
                "17",
                "3.3.4",
                "5.1.2",
                Map.of(
                        "java", "17",
                        "spring-boot", "3.3.4",
                        "spring-batch", "5.1.2",
                        "spring-framework", "6.1.13",
                        "hibernate", "6.5.3.Final",
                        "jakarta-ee", "10",
                        "junit", "5.10.3",
                        "slf4j", "2.0.16",
                        "logback", "1.5.8"
                )
        );
    }

    public static TargetProfile java21Profile() {
        return new TargetProfile(
                "java-21-spring-6",
                "Java 21 LTS & Spring Boot 3.3 (Recommandé)",
                "Migration vers Java 21 LTS (Virtual Threads), Spring Boot 3.3.x, Spring Batch 5.1.x et Jakarta EE 10",
                "21",
                "3.3.4",
                "5.1.2",
                Map.of(
                        "java", "21",
                        "spring-boot", "3.3.4",
                        "spring-batch", "5.1.2",
                        "spring-framework", "6.1.13",
                        "hibernate", "6.5.3.Final",
                        "jakarta-ee", "10",
                        "junit", "5.10.3",
                        "slf4j", "2.0.16",
                        "logback", "1.5.8"
                )
        );
    }

    public static TargetProfile java25Profile() {
        return new TargetProfile(
                "java-25-spring-7",
                "Java 25 LTS & Spring Boot 4 (Spring 7 Future)",
                "Migration avant-gardiste vers Java 25 LTS, Spring Boot 4.0.x / Spring 7, Spring Batch 6.x et Jakarta EE 11",
                "25",
                "4.0.0-M1",
                "6.0.0-M1",
                Map.of(
                        "java", "25",
                        "spring-boot", "4.0.0-M1",
                        "spring-batch", "6.0.0-M1",
                        "spring-framework", "7.0.0-M1",
                        "hibernate", "7.0.0.Alpha1",
                        "jakarta-ee", "11",
                        "junit", "5.11.0",
                        "slf4j", "2.1.0",
                        "logback", "1.5.12"
                )
        );
    }

    public static TargetProfile create(String javaVer, String springVer, String batchVer) {
        String cleanJava = (javaVer != null && !javaVer.isBlank()) ? javaVer.trim() : "21";
        String cleanSpring = (springVer != null && !springVer.isBlank()) ? springVer.trim() : "3.3.4";
        String cleanBatch = (batchVer != null && !batchVer.isBlank()) ? batchVer.trim() : "5.1.2";

        if ("25".equals(cleanJava) || cleanSpring.startsWith("4") || cleanSpring.contains("Spring 7")) {
            return java25Profile();
        } else if ("17".equals(cleanJava)) {
            return java17Profile();
        } else {
            return java21Profile();
        }
    }
}
