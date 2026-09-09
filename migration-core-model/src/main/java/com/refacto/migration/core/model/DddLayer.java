package com.refacto.migration.core.model;

/**
 * Couches DDD standard pour la composition et l'isolation des modules.
 */
public enum DddLayer {
    DOMAIN("Couche Domaine", "Entités métier pures, Value Objects, interfaces de Repository (agnostique des frameworks)"),
    APPLICATION("Couche Application", "Cas d'usage, orchestration, services de batch, DTOs"),
    INFRASTRUCTURE("Couche Infrastructure", "Lecteurs/Scripteurs Spring Batch, implémentations JPA/DAO, connecteurs SQL/fichiers"),
    COMMONS("Bibliothèque Transverse", "Utilitaires techniques mutualisés (strings, dates, chiffrement, formatage)");

    private final String label;
    private final String description;

    DddLayer(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
