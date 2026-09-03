package com.refacto.migration.recipe;

import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.Dependency;
import com.refacto.migration.core.model.Finding;
import com.refacto.migration.core.model.ModuleDescriptor;
import com.refacto.migration.core.model.RiskAssessment;

import java.util.ArrayList;
import java.util.List;

/**
 * Moteur de calcul du score de risque de migration (0 à 100) et explicabilité détaillée (Section 38).
 */
public class RiskScoringService {

    public RiskAssessment assessRisk(List<ModuleDescriptor> modules, List<Dependency> dependencies, List<Finding> findings) {
        List<RiskAssessment.RiskFactor> factors = new ArrayList<>();
        int rawScore = 0;

        // 1. Breaking Framework Changes & Dependencies
        long breakingDeps = dependencies.stream().filter(d -> d.breakingChangeCount() > 0).count();
        if (breakingDeps > 0) {
            int contrib = (int) Math.min(25, breakingDeps * 8);
            rawScore += contrib;
            factors.add(new RiskAssessment.RiskFactor(
                    "FRAMEWORK_BREAKING_CHANGES",
                    breakingDeps + " dépendances majeures avec breaking changes détectées (Spring Boot, Spring Batch, Hibernate).",
                    25,
                    contrib,
                    "Nécessite des modifications d'APIs et de configuration substantielles."
            ));
        }

        // 2. Dependency Conflicts
        long conflicts = dependencies.stream().filter(Dependency::conflict).count();
        if (conflicts > 0) {
            int contrib = (int) Math.min(15, conflicts * 5);
            rawScore += contrib;
            factors.add(new RiskAssessment.RiskFactor(
                    "DEPENDENCY_CONFLICTS",
                    conflicts + " conflits de versions entre modules découverts.",
                    15,
                    contrib,
                    "Risque de NoSuchMethodError ou incompatibilités transitives au runtime."
            ));
        }

        // 3. Database & Concurrency Risks (SELECT FOR UPDATE, Loop DB calls, Bulk updates)
        long dbCriticals = findings.stream()
                .filter(f -> f.recipeId().startsWith("APP-DB") && (f.severity() == Severity.HIGH || f.severity() == Severity.CRITICAL))
                .count();
        if (dbCriticals > 0) {
            int contrib = (int) Math.min(30, dbCriticals * 6);
            rawScore += contrib;
            factors.add(new RiskAssessment.RiskFactor(
                    "DATABASE_CONCURRENCY",
                    dbCriticals + " anomalies critiques de persistance ou concurrence (SELECT FOR UPDATE, boucles DB, chunk updates).",
                    30,
                    contrib,
                    "Risque direct de deadlocks, dépassement des fenêtres batch ou saturation de la base de données."
            ));
        }

        // 4. Architectural Coupling (Shared business components)
        long archCoupling = findings.stream()
                .filter(f -> f.recipeId().equals("APP-ARCH-001"))
                .count();
        if (archCoupling > 0) {
            int contrib = (int) Math.min(15, archCoupling * 5);
            rawScore += contrib;
            factors.add(new RiskAssessment.RiskFactor(
                    "ARCHITECTURAL_COUPLING",
                    archCoupling + " composants métier transverses fortement partagés entre batchs.",
                    15,
                    contrib,
                    "Difficulté de déploiement indépendant et risque d'effets de bord non maîtrisés."
            ));
        }

        // 5. Code Complexity & SRP
        long srpViolations = findings.stream()
                .filter(f -> f.recipeId().equals("APP-CODE-001"))
                .count();
        if (srpViolations > 0) {
            int contrib = (int) Math.min(15, srpViolations * 3);
            rawScore += contrib;
            factors.add(new RiskAssessment.RiskFactor(
                    "CODE_COMPLEXITY",
                    srpViolations + " classes monolithiques violant le Single Responsibility Principle.",
                    15,
                    contrib,
                    "Refactoring manuel complexe nécessitant des analyses au cas par cas."
            ));
        }

        // 6. Multi-Module Project Scale & Build Complexity
        if (modules.size() >= 3) {
            int contrib = Math.min(15, modules.size() * 3);
            rawScore += contrib;
            factors.add(new RiskAssessment.RiskFactor(
                    "PROJECT_SCALE_MODULES",
                    modules.size() + " modules Maven interdépendants nécessitant un ordonnancement par vagues.",
                    15,
                    contrib,
                    "Coordination de build et tests d'intégration multi-modules requis."
            ));
        }

        // 7. Java Version & Framework Jump
        boolean hasBatchOrJpa = modules.stream().anyMatch(m -> m.hasSpringBatch() || m.hasJpa());
        if (hasBatchOrJpa) {
            rawScore += 15;
            factors.add(new RiskAssessment.RiskFactor(
                    "FRAMEWORK_TRANSITION",
                    "Migration conjointe Spring Batch 4 vers 5 et Java 11 vers 17.",
                    20,
                    15,
                    "Changements structurels de configuration, de packaging (jakarta) et de cycle de vie batch."
            ));
        }

        int finalScore = Math.min(100, Math.max(5, rawScore));
        String level = finalScore >= 70 ? "HIGH" : (finalScore >= 40 ? "MEDIUM" : "LOW");

        String summary = String.format("Score de risque global de migration : %d/100 (%s). Principaux facteurs : %s.",
                finalScore, level,
                factors.isEmpty() ? "Risque minimal" : factors.get(0).description());

        return new RiskAssessment(finalScore, level, factors, summary);
    }
}
