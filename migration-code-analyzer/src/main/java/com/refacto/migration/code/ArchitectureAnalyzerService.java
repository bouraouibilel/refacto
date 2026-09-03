package com.refacto.migration.code;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.CouplingLevel;
import com.refacto.migration.core.enums.FindingStatus;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.ArchitectureCoupling;
import com.refacto.migration.core.model.BatchDescriptor;
import com.refacto.migration.core.model.Finding;
import com.refacto.migration.core.model.ModuleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Analyseur d'architecture (APP-ARCH-001 Composants métier partagés, APP-ARCH-002 Éligibilité APP-commons).
 */
public class ArchitectureAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureAnalyzerService.class);

    private static final Set<String> TECHNICAL_COMMONS_KEYWORDS = Set.of(
            "util", "helper", "string", "date", "csv", "xml", "json", "hash", "encrypt", "io", "file"
    );

    public record ArchitectureAnalysisResult(
            List<ArchitectureCoupling> couplings,
            List<Finding> findings
    ) {}

    public ArchitectureAnalysisResult analyzeArchitecture(Path projectRoot, List<ModuleDescriptor> modules, List<BatchDescriptor> batches) {
        List<ArchitectureCoupling> couplings = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        Map<String, Set<String>> classToBatchConsumers = new HashMap<>();
        Map<String, Set<String>> classToModuleConsumers = new HashMap<>();
        Map<String, String> classDefinitions = new HashMap<>();

        // 1. Scan all modules to inventory classes and their types
        for (ModuleDescriptor module : modules) {
            Path moduleDir = projectRoot.resolve(module.relativePath());
            Path srcMain = moduleDir.resolve("src/main/java");
            if (!Files.exists(srcMain)) continue;

            try (Stream<Path> stream = Files.walk(srcMain)) {
                stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                        .forEach(javaFile -> {
                            try {
                                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                                for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                                    String className = cid.getNameAsString();
                                    classDefinitions.put(className, module.artifactId());

                                    // Check imports and usages in this file
                                    cu.getImports().forEach(imp -> {
                                        String imported = imp.getNameAsString();
                                        int lastDot = imported.lastIndexOf('.');
                                        if (lastDot > 0) {
                                            String simpleName = imported.substring(lastDot + 1);
                                            classToModuleConsumers.computeIfAbsent(simpleName, k -> new HashSet<>()).add(module.artifactId());
                                        }
                                    });
                                }
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }

        // 2. Associate Batch Jobs with used classes
        for (BatchDescriptor batch : batches) {
            batch.steps().forEach(step -> {
                if (step.readerClass() != null) trackConsumer(step.readerClass(), batch.jobName(), classToBatchConsumers);
                if (step.processorClass() != null) trackConsumer(step.processorClass(), batch.jobName(), classToBatchConsumers);
                if (step.writerClass() != null) trackConsumer(step.writerClass(), batch.jobName(), classToBatchConsumers);
            });
        }

        // 3. Evaluate APP-ARCH-001 (Shared business components)
        for (Map.Entry<String, String> entry : classDefinitions.entrySet()) {
            String className = entry.getKey();
            String definedInModule = entry.getValue();

            Set<String> consumingBatches = classToBatchConsumers.getOrDefault(className, Collections.emptySet());
            Set<String> consumingModules = classToModuleConsumers.getOrDefault(className, Collections.emptySet());

            boolean isBusinessComponent = className.contains("DAO") || className.contains("Repository") ||
                                          className.contains("Service") || className.contains("Entity") ||
                                          className.contains("Customer") || className.contains("Payment");

            boolean isTechnicalHelper = isTechnical(className);

            CouplingLevel level = CouplingLevel.LOW;
            if (consumingModules.size() >= 2 || consumingBatches.size() >= 2) {
                level = CouplingLevel.HIGH;
            } else if (consumingModules.size() >= 1 || consumingBatches.size() >= 1) {
                level = CouplingLevel.MEDIUM;
            }

            if (isBusinessComponent && (level == CouplingLevel.HIGH || level == CouplingLevel.MEDIUM)) {
                ArchitectureCoupling coupling = new ArchitectureCoupling(
                        className,
                        resolveComponentType(className),
                        definedInModule,
                        new ArrayList<>(consumingBatches),
                        new ArrayList<>(consumingModules),
                        level,
                        false, // Never eligible for commons
                        "Spécialiser le composant au niveau du batch ou lot de batchs pour réduire le couplage transverse."
                );
                couplings.add(coupling);

                findings.add(new Finding(
                        "APP-ARCH-001-" + UUID.randomUUID().toString().substring(0, 8),
                        null, null, definedInModule,
                        "APP-ARCH-001", "1.0",
                        Category.ARCHITECTURE, Severity.HIGH, 95,
                        AutomationLevel.MANUAL_ONLY,
                        definedInModule + "/" + className + ".java",
                        1, 1, className,
                        String.format("Composant métier partagé '%s' fortement couplé (%d modules, %d batchs).",
                                className, consumingModules.size(), consumingBatches.size()),
                        "La roadmap APP vise à réduire le couplage en spécialisant les composants métier au niveau du batch. Un composant métier partagé doit rester exceptionnel.",
                        "Effet de bord lors des montées de version ou évolutions de schéma",
                        "Dupliquer ou scinder le composant métier par domaine batch dédié.",
                        16.0, "High", FindingStatus.OPEN,
                        "Shared by: " + String.join(", ", consumingModules),
                        null, null
                ));
            }

            // 4. APP-ARCH-002: Evaluate APP-commons eligibility
            if (isTechnicalHelper && !isBusinessComponent) {
                couplings.add(new ArchitectureCoupling(
                        className,
                        "TECHNICAL_UTILITY",
                        definedInModule,
                        new ArrayList<>(consumingBatches),
                        new ArrayList<>(consumingModules),
                        level,
                        true, // Eligible for APP-commons!
                        "Composant technique éligible à la bibliothèque partagée 'APP-commons'."
                ));
            }
        }

        return new ArchitectureAnalysisResult(couplings, findings);
    }

    private void trackConsumer(String classOrExpr, String batchName, Map<String, Set<String>> map) {
        if (classOrExpr == null) return;
        String clean = classOrExpr.replaceAll("[^a-zA-Z0-9_]", " ");
        for (String word : clean.split(" ")) {
            if (word.length() > 3 && Character.isUpperCase(word.charAt(0))) {
                map.computeIfAbsent(word, k -> new HashSet<>()).add(batchName);
            }
        }
    }

    private boolean isTechnical(String className) {
        String lower = className.toLowerCase();
        return TECHNICAL_COMMONS_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private String resolveComponentType(String className) {
        if (className.contains("DAO")) return "DAO";
        if (className.contains("Repository")) return "Repository";
        if (className.contains("Entity")) return "JPA_ENTITY";
        if (className.contains("Service")) return "SERVICE";
        if (className.contains("DTO")) return "DTO";
        return "BUSINESS_COMPONENT";
    }
}
