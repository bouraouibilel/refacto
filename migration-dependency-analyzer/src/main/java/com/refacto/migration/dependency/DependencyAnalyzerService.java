package com.refacto.migration.dependency;

import com.refacto.migration.core.model.Dependency;
import com.refacto.migration.core.model.ModuleDescriptor;
import com.refacto.migration.core.model.TargetProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service d'analyse d'inventaire de dépendances, de conflits et d'impacts de migration (Sections 11 à 14).
 */
public class DependencyAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzerService.class);

    // Knowledge base of major library migrations
    private static final Map<String, LibraryMigrationSpec> MIGRATION_SPECS = new HashMap<>();

    static {
        MIGRATION_SPECS.put("org.springframework.batch:spring-batch-core", new LibraryMigrationSpec(
                "5.1.x",
                List.of(
                        "JobBuilderFactory (supprimé dans v5)",
                        "StepBuilderFactory (supprimé dans v5)",
                        "Passage javax.sql.DataSource -> jakarta",
                        "TransactionManager explicite obligatoire dans step"
                ),
                true
        ));
        MIGRATION_SPECS.put("org.springframework.boot:spring-boot", new LibraryMigrationSpec(
                "3.3.x",
                List.of(
                        "Baseline Java 17 minimum",
                        "javax.* -> jakarta.* package transition",
                        "Spring MVC request mapping / security changes"
                ),
                true
        ));
        MIGRATION_SPECS.put("org.hibernate:hibernate-core", new LibraryMigrationSpec(
                "6.5.x",
                List.of(
                        "Jakarta Persistence 3.x namespace",
                        "Remplacement de org.hibernate.Criteria",
                        "Nouveau type system et mapping JDBC"
                ),
                true
        ));
        MIGRATION_SPECS.put("junit:junit", new LibraryMigrationSpec(
                "5.10.x",
                List.of(
                        "org.junit.Test -> org.junit.jupiter.api.Test",
                        "@Before/@After -> @BeforeEach/@AfterEach",
                        "@Ignore -> @Disabled",
                        "Remplacement de @Rule / ExpectedException par assertThrows"
                ),
                false
        ));
    }

    public List<Dependency> buildInventory(List<ModuleDescriptor> modules, TargetProfile targetProfile) {
        Map<String, List<ModuleDepOccurrence>> occurrences = new HashMap<>();

        for (ModuleDescriptor module : modules) {
            for (String depCoord : module.dependencies()) {
                String[] parts = depCoord.split(":");
                if (parts.length >= 2) {
                    String groupId = parts[0];
                    String artifactId = parts[1];
                    String version = parts.length >= 3 ? parts[2] : "managed";
                    String key = groupId + ":" + artifactId;

                    occurrences.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new ModuleDepOccurrence(module.artifactId(), version, "compile", true));
                }
            }
        }

        List<Dependency> result = new ArrayList<>();

        for (Map.Entry<String, List<ModuleDepOccurrence>> entry : occurrences.entrySet()) {
            String coord = entry.getKey();
            String[] parts = coord.split(":");
            String groupId = parts[0];
            String artifactId = parts[1];

            List<ModuleDepOccurrence> occList = entry.getValue();
            List<String> moduleNames = occList.stream().map(ModuleDepOccurrence::moduleName).distinct().toList();

            Set<String> distinctVersions = new HashSet<>();
            for (ModuleDepOccurrence occ : occList) {
                if (!"managed".equalsIgnoreCase(occ.version())) {
                    distinctVersions.add(occ.version());
                }
            }

            boolean conflict = distinctVersions.size() > 1;
            String currentVersion = distinctVersions.isEmpty() ? "managed" : String.join(" / ", distinctVersions);

            String targetVersion = resolveTargetVersion(groupId, artifactId, targetProfile);
            LibraryMigrationSpec spec = MIGRATION_SPECS.get(coord);

            int breakingChangeCount = 0;
            String migrationStatus = "UP_TO_DATE";

            if (targetVersion != null && !targetVersion.equals(currentVersion)) {
                migrationStatus = "UPGRADE_REQUIRED";
                if (spec != null) {
                    breakingChangeCount = spec.breakingChanges().size();
                    if (spec.highRisk()) {
                        migrationStatus = "BREAKING_MIGRATION";
                    }
                }
            }

            result.add(new Dependency(
                    groupId,
                    artifactId,
                    currentVersion,
                    targetVersion != null ? targetVersion : currentVersion,
                    "compile",
                    true,
                    moduleNames,
                    "pom.xml",
                    conflict,
                    migrationStatus,
                    breakingChangeCount
            ));
        }

        // Sort by breaking change count desc, then conflict desc, then artifactId
        result.sort(Comparator.comparingInt(Dependency::breakingChangeCount).reversed()
                .thenComparing(Dependency::conflict, Comparator.reverseOrder())
                .thenComparing(Dependency::artifactId));

        return result;
    }

    public List<String> getBreakingChanges(String groupId, String artifactId) {
        String key = groupId + ":" + artifactId;
        LibraryMigrationSpec spec = MIGRATION_SPECS.get(key);
        return spec != null ? spec.breakingChanges() : Collections.emptyList();
    }

    private String resolveTargetVersion(String groupId, String artifactId, TargetProfile targetProfile) {
        if (targetProfile == null || targetProfile.targets() == null) {
            return null;
        }

        Map<String, String> targets = targetProfile.targets();

        if (groupId.contains("springframework.boot")) {
            return targets.getOrDefault("spring-boot", "3.3.x");
        }
        if (groupId.contains("springframework.batch")) {
            return targets.getOrDefault("spring-batch", "5.1.x");
        }
        if (groupId.contains("hibernate")) {
            return targets.getOrDefault("hibernate", "6.5.x");
        }
        if (groupId.equals("junit") && artifactId.equals("junit")) {
            return targets.getOrDefault("junit", "5.10.x");
        }

        return null;
    }

    private record ModuleDepOccurrence(String moduleName, String version, String scope, boolean direct) {}

    private record LibraryMigrationSpec(String recommendedTarget, List<String> breakingChanges, boolean highRisk) {}
}
