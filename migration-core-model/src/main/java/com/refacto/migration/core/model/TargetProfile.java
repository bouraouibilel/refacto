package com.refacto.migration.core.model;

import java.util.Map;

public record TargetProfile(
        String id,
        String name,
        String description,
        Map<String, String> targets // e.g. java: "17", spring-boot: "3.x", spring-batch: "5.x", hibernate: "6.x", junit: "5.x"
) {
    public static TargetProfile defaultJava17Profile() {
        return new TargetProfile(
                "java-modernization-2026",
                "Migration Java 17 & Frameworks Modernes",
                "Profil de modernisation vers Java 17, Spring Boot 3, Spring Batch 5, Hibernate 6 et JUnit 5",
                Map.of(
                        "java", "17",
                        "spring-boot", "3.x",
                        "spring-batch", "5.x",
                        "hibernate", "6.x",
                        "junit", "5.x"
                )
        );
    }
}
