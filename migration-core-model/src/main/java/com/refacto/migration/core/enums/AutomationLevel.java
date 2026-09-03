package com.refacto.migration.core.enums;

/**
 * Niveaux d'automatisation des transformations.
 */
public enum AutomationLevel {
    /**
     * Transformation déterministe et à très faible risque (ex: javax -> jakarta).
     */
    AUTO_SAFE,

    /**
     * Transformation automatisable mais nécessitant compilation et tests.
     */
    AUTO_WITH_TESTS,

    /**
     * Proposition générée nécessitant impérativement une validation humaine.
     */
    REVIEW_REQUIRED,

    /**
     * Diagnostic et recommandations uniquement, correction manuelle requise.
     */
    MANUAL_ONLY
}
