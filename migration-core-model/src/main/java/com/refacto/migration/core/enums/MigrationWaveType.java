package com.refacto.migration.core.enums;

public enum MigrationWaveType {
    WAVE_0_BASELINE(0, "Baseline & État des lieux"),
    WAVE_1_JAVA(1, "Modernisation Java (17/21)"),
    WAVE_2_BUILD_DEPS(2, "Dépendances de build & Plugins"),
    WAVE_3_FRAMEWORKS(3, "Montée de version Frameworks (Spring Boot / Batch)"),
    WAVE_4_DB_OPTIM(4, "Optimisation Base de données (APP-DB)"),
    WAVE_5_CODE_MODERN(5, "Modernisation Code (SRP, Abstraction, Null safety)"),
    WAVE_6_ARCH_DECOUPLING(6, "Découplage Architecture & APP-commons"),
    WAVE_7_FINAL_VALIDATION(7, "Validation finale & Non-régression");

    private final int order;
    private final String description;

    MigrationWaveType(int order, String description) {
        this.order = order;
        this.description = description;
    }

    public int getOrder() {
        return order;
    }

    public String getDescription() {
        return description;
    }
}
