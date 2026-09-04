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
    private static final Map<String, String> DEFAULT_LEGACY_VERSIONS = new HashMap<>();

    static {
        // Known default legacy versions from Spring Boot 2.x / Java 8-11 BOMs
        DEFAULT_LEGACY_VERSIONS.put("org.slf4j:slf4j-api", "1.7.36 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("ch.qos.logback:logback-classic", "1.2.12 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.slf4j:jcl-over-slf4j", "1.7.36 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.springframework.batch:spring-batch-core", "4.3.8 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.springframework.boot:spring-boot", "2.7.18 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.springframework.boot:spring-boot-starter-batch", "2.7.18 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.springframework.boot:spring-boot-starter-web", "2.7.18 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.springframework.boot:spring-boot-starter-data-jpa", "2.7.18 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.hibernate:hibernate-core", "5.6.15.Final (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("javax.persistence:javax.persistence-api", "2.2 (javax)");
        DEFAULT_LEGACY_VERSIONS.put("javax.servlet:javax.servlet-api", "4.0.1 (javax)");
        DEFAULT_LEGACY_VERSIONS.put("junit:junit", "4.13.2 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.projectlombok:lombok", "1.18.24 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("org.apache.commons:commons-lang3", "3.12.0 (BOM)");
        DEFAULT_LEGACY_VERSIONS.put("com.fasterxml.jackson.core:jackson-databind", "2.13.5 (BOM)");

        MIGRATION_SPECS.put("org.springframework.batch:spring-batch-core", new LibraryMigrationSpec(
                "5.1.2",
                List.of(
                        "JobBuilderFactory supprimé (utiliser JobBuilder avec JobRepository explicite)",
                        "StepBuilderFactory supprimé (utiliser StepBuilder avec TransactionManager explicite)",
                        "Passage javax.sql.DataSource -> jakarta.sql.DataSource",
                        "Nouveau schéma de métadonnées Spring Batch 5 (colonnes SEQUENCE_NAME, BATCH_STEP_EXECUTION)"
                ),
                true
        ));
        MIGRATION_SPECS.put("org.springframework.boot:spring-boot", new LibraryMigrationSpec(
                "3.3.4",
                List.of(
                        "Baseline Java 17 minimum (compatible Java 21 LTS et Java 25)",
                        "Bascule globale du namespace javax.* vers jakarta.* (Servlets 6, JPA 3.1)",
                        "Changements de configuration de sécurité SecurityFilterChain",
                        "Obsolescence des propriétés spring.datasource.* legacy"
                ),
                true
        ));
        MIGRATION_SPECS.put("org.hibernate:hibernate-core", new LibraryMigrationSpec(
                "6.5.3.Final",
                List.of(
                        "Jakarta Persistence 3.x namespace",
                        "Remplacement total de org.hibernate.Criteria par JPA Criteria API",
                        "Évolution du type mapping JDBC et conversion des dates java.time.*"
                ),
                true
        ));
        MIGRATION_SPECS.put("ch.qos.logback:logback-classic", new LibraryMigrationSpec(
                "1.5.8",
                List.of(
                        "Nécessite SLF4J 2.0+ (ServiceLoader provider model)",
                        "Suppression de l'implémentation statique org.slf4j.impl.StaticLoggerBinder",
                        "Support des Virtual Threads et Java 21"
                ),
                false
        ));
        MIGRATION_SPECS.put("org.slf4j:slf4j-api", new LibraryMigrationSpec(
                "2.0.16",
                List.of(
                        "Migration vers l'architecture Fluent Logging API",
                        "Chargement par java.util.ServiceLoader au lieu de StaticLoggerBinder"
                ),
                false
        ));
        MIGRATION_SPECS.put("junit:junit", new LibraryMigrationSpec(
                "5.10.3",
                List.of(
                        "org.junit.Test -> org.junit.jupiter.api.Test",
                        "@Before/@After -> @BeforeEach/@AfterEach",
                        "@Ignore -> @Disabled",
                        "Remplacement de @Rule / ExpectedException par Assertions.assertThrows"
                ),
                false
        ));
    }

    public List<Dependency> buildInventory(List<ModuleDescriptor> modules, TargetProfile targetProfile) {
        Set<String> internalArtifactIds = new HashSet<>();
        Set<String> internalGroupIds = new HashSet<>();
        for (ModuleDescriptor mod : modules) {
            internalArtifactIds.add(mod.artifactId());
            if (mod.groupId() != null && !mod.groupId().equals("unknown")) {
                internalGroupIds.add(mod.groupId());
            }
        }

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
                if (!"managed".equalsIgnoreCase(occ.version()) && !occ.version().isBlank()) {
                    distinctVersions.add(occ.version());
                }
            }

            boolean conflict = distinctVersions.size() > 1;
            String currentVersion;
            if (!distinctVersions.isEmpty()) {
                currentVersion = String.join(" / ", distinctVersions);
            } else {
                currentVersion = inferLegacyVersion(coord, groupId, artifactId, internalArtifactIds, internalGroupIds);
            }

            String targetVersion = resolveTargetVersion(groupId, artifactId, targetProfile);
            if (targetVersion == null) {
                targetVersion = inferTargetVersion(groupId, artifactId, currentVersion, targetProfile, internalArtifactIds, internalGroupIds);
            }

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
                } else if (currentVersion.contains("javax") || groupId.contains("javax") ||
                           groupId.contains("springframework") || groupId.contains("hibernate")) {
                    breakingChangeCount = 2;
                    migrationStatus = "BREAKING_MIGRATION";
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

    private String inferLegacyVersion(String coord, String groupId, String artifactId,
                                      Set<String> internalArtifacts, Set<String> internalGroups) {
        if (internalArtifacts.contains(artifactId) || internalGroups.contains(groupId)) {
            return "${revision} (Projet)";
        }
        if (DEFAULT_LEGACY_VERSIONS.containsKey(coord)) {
            return DEFAULT_LEGACY_VERSIONS.get(coord);
        }
        if (groupId.startsWith("org.springframework.boot")) return "2.7.18 (BOM)";
        if (groupId.startsWith("org.springframework.batch")) return "4.3.8 (BOM)";
        if (groupId.startsWith("org.springframework.security")) return "5.8.14 (BOM)";
        if (groupId.startsWith("org.springframework.data")) return "2.7.18 (BOM)";
        if (groupId.startsWith("org.springframework")) return "5.3.39 (BOM)";
        if (groupId.startsWith("org.hibernate")) return "5.6.15.Final (BOM)";
        if (groupId.startsWith("org.slf4j")) return "1.7.36 (BOM)";
        if (groupId.startsWith("ch.qos.logback")) return "1.2.12 (BOM)";
        if (groupId.startsWith("com.fasterxml.jackson")) return "2.13.5 (BOM)";
        if (groupId.startsWith("org.projectlombok")) return "1.18.24 (BOM)";
        if (groupId.startsWith("org.apache.commons") && artifactId.contains("lang")) return "3.12.0 (BOM)";
        if (groupId.startsWith("org.apache.commons") && artifactId.contains("collections")) return "4.4 (BOM)";
        if (groupId.contains("commons-io")) return "2.11.0 (BOM)";
        if (groupId.contains("oracle") || artifactId.contains("ojdbc")) return "19.3.0.0 (BOM)";
        if (groupId.contains("postgresql")) return "42.3.8 (BOM)";
        if (groupId.contains("mockito")) return "3.12.4 (BOM)";
        if (groupId.contains("assertj")) return "3.22.0 (BOM)";
        if (groupId.contains("mapstruct")) return "1.4.2.Final (BOM)";
        if (groupId.startsWith("javax.persistence")) return "2.2.3 (javax)";
        if (groupId.startsWith("javax.servlet")) return "4.0.1 (javax)";
        if (groupId.startsWith("javax.transaction")) return "1.3.3 (javax)";
        if (groupId.startsWith("javax.annotation")) return "1.3.2 (javax)";

        return "BOM Héritée (Parent POM)";
    }

    private String resolveTargetVersion(String groupId, String artifactId, TargetProfile targetProfile) {
        if (targetProfile == null || targetProfile.targets() == null) {
            return null;
        }

        Map<String, String> targets = targetProfile.targets();

        if (groupId.contains("springframework.batch")) {
            return targets.getOrDefault("spring-batch", "5.1.2");
        }
        if (groupId.contains("springframework.boot")) {
            return targets.getOrDefault("spring-boot", "3.3.4");
        }
        if (groupId.contains("springframework")) {
            return targets.getOrDefault("spring-framework", "6.1.13");
        }
        if (groupId.contains("hibernate")) {
            return targets.getOrDefault("hibernate", "6.5.3.Final");
        }
        if (groupId.equals("org.slf4j")) {
            return targets.getOrDefault("slf4j", "2.0.16");
        }
        if (groupId.equals("ch.qos.logback")) {
            return targets.getOrDefault("logback", "1.5.8");
        }
        if (groupId.equals("junit") && artifactId.equals("junit")) {
            return targets.getOrDefault("junit", "5.10.3");
        }

        return null;
    }

    private String inferTargetVersion(String groupId, String artifactId, String currentVersion,
                                      TargetProfile targetProfile, Set<String> internalArtifacts, Set<String> internalGroups) {
        if (internalArtifacts.contains(artifactId) || internalGroups.contains(groupId)) {
            return "${revision}";
        }

        boolean isJava25 = targetProfile != null && "25".equals(targetProfile.targetJavaVersion());
        String sbVer = targetProfile != null && targetProfile.targetSpringBootVersion() != null ?
                targetProfile.targetSpringBootVersion() : "3.3.4";
        String batchVer = targetProfile != null && targetProfile.targetSpringBatchVersion() != null ?
                targetProfile.targetSpringBatchVersion() : "5.1.2";

        if (groupId.startsWith("org.springframework.batch")) {
            return batchVer;
        }
        if (groupId.startsWith("org.springframework.boot")) {
            return sbVer;
        }
        if (groupId.startsWith("org.springframework")) {
            return isJava25 ? "7.0.0-M1" : "6.1.13";
        }
        if (groupId.startsWith("org.hibernate")) {
            return isJava25 ? "7.0.0.Alpha1" : "6.5.3.Final";
        }
        if (groupId.startsWith("org.slf4j")) {
            return isJava25 ? "2.1.0" : "2.0.16";
        }
        if (groupId.startsWith("ch.qos.logback")) {
            return isJava25 ? "1.5.12" : "1.5.8";
        }
        if (groupId.startsWith("javax.persistence") || artifactId.contains("persistence-api")) {
            return isJava25 ? "3.2.0 (jakarta)" : "3.1.0 (jakarta)";
        }
        if (groupId.startsWith("javax.servlet") || artifactId.contains("servlet-api")) {
            return isJava25 ? "6.1.0 (jakarta)" : "6.0.0 (jakarta)";
        }
        if (groupId.startsWith("javax.transaction") || artifactId.contains("transaction-api")) {
            return "2.0.1 (jakarta)";
        }
        if (groupId.startsWith("javax.annotation") || artifactId.contains("annotation-api")) {
            return "2.1.1 (jakarta)";
        }
        if (groupId.contains("jackson")) {
            return isJava25 ? "2.18.0" : "2.17.2";
        }
        if (groupId.contains("lombok")) {
            return "1.18.34";
        }
        if (groupId.contains("commons-lang")) {
            return "3.17.0";
        }
        if (groupId.contains("commons-io")) {
            return "2.16.1";
        }
        if (groupId.contains("commons-collections")) {
            return "4.4";
        }
        if (groupId.contains("oracle") || artifactId.contains("ojdbc")) {
            return "23.4.0.24.05";
        }
        if (groupId.contains("postgresql")) {
            return "42.7.4";
        }
        if (groupId.contains("mockito")) {
            return "5.11.0";
        }
        if (groupId.contains("assertj")) {
            return "3.26.3";
        }
        if (groupId.contains("mapstruct")) {
            return "1.5.5.Final";
        }
        if (groupId.equals("junit") && artifactId.equals("junit")) {
            return "5.10.3 (Jupiter)";
        }

        // Si la version actuelle est héritée de la BOM legacy, aligner précisément sur la version de la BOM cible
        if (currentVersion.contains("BOM") || currentVersion.startsWith("1.") || currentVersion.startsWith("2.")) {
            return sbVer + " (BOM Cible)";
        }

        return currentVersion;
    }

    private record ModuleDepOccurrence(String moduleName, String version, String scope, boolean direct) {}

    private record LibraryMigrationSpec(String recommendedTarget, List<String> breakingChanges, boolean highRisk) {}
}
