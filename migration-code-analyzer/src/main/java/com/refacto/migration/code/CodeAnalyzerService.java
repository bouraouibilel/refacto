package com.refacto.migration.code;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.FindingStatus;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Analyseur de modernisation de code et d'architecture (APP-CODE-001, APP-CODE-002, Java 17).
 */
public class CodeAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzerService.class);

    public List<Finding> analyzeModule(Path projectRoot, String moduleName, Path moduleDir) {
        List<Finding> findings = new ArrayList<>();
        Path srcMain = moduleDir.resolve("src/main/java");
        if (!Files.exists(srcMain)) {
            return findings;
        }

        try (Stream<Path> stream = Files.walk(srcMain)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .forEach(javaFile -> {
                        try {
                            analyzeSourceFile(javaFile, moduleName, projectRoot, findings);
                        } catch (Exception e) {
                            log.debug("Erreur analyse AST {}: {}", javaFile, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Erreur parcours {}: {}", srcMain, e.getMessage());
        }

        return findings;
    }

    private void analyzeSourceFile(Path javaFile, String moduleName, Path projectRoot, List<Finding> findings) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        String relativePath = projectRoot.relativize(javaFile).toString().replace('\\', '/');

        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            checkSingleResponsibility(cid, relativePath, moduleName, findings);
            checkAbstractionLevel(cid, relativePath, moduleName, findings);
            checkBatchModernization(cid, relativePath, moduleName, findings);
            checkJava17Modernization(cid, relativePath, moduleName, findings);
            checkNullSafetyAndOptional(cid, relativePath, moduleName, findings);
        }
    }

    /**
     * APP-CODE-001: Single Responsibility Principle
     */
    private void checkSingleResponsibility(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        int loc = cid.getEnd().map(p -> p.line).orElse(0) - cid.getBegin().map(p -> p.line).orElse(0);
        int methodCount = cid.getMethods().size();

        Set<String> responsibilities = new LinkedHashSet<>();
        boolean hasDao = false;
        boolean hasFileIo = false;
        boolean hasValidation = false;
        boolean hasNotification = false;

        for (FieldDeclaration field : cid.getFields()) {
            String fType = field.getElementType().asString().toLowerCase();
            if (fType.contains("dao") || fType.contains("repository") || fType.contains("entitymanager")) {
                hasDao = true;
                responsibilities.add("database operations");
            }
            if (fType.contains("writer") || fType.contains("csv") || fType.contains("file")) {
                hasFileIo = true;
                responsibilities.add("file I/O generation");
            }
            if (fType.contains("validator") || fType.contains("rule")) {
                hasValidation = true;
                responsibilities.add("business validation");
            }
            if (fType.contains("mail") || fType.contains("notify") || fType.contains("notification")) {
                hasNotification = true;
                responsibilities.add("notifications / external alerting");
            }
        }

        for (MethodDeclaration method : cid.getMethods()) {
            String mName = method.getNameAsString().toLowerCase();
            if (mName.contains("validate") || mName.contains("check")) responsibilities.add("business validation");
            if (mName.contains("csv") || mName.contains("export") || mName.contains("writefile")) responsibilities.add("file I/O generation");
            if (mName.contains("notify") || mName.contains("sendmail")) responsibilities.add("notifications / external alerting");
        }

        if (responsibilities.size() >= 3 || (responsibilities.size() >= 2 && loc > 250)) {
            String className = cid.getNameAsString();
            String desc = String.format("La classe '%s' cumule %d responsabilités distinctes (%s) sur %d lignes et %d méthodes.",
                    className, responsibilities.size(), String.join(", ", responsibilities), loc, methodCount);

            StringBuilder suggestedClasses = new StringBuilder("Décomposer la classe en composants spécialisés :\n");
            suggestedClasses.append(" - ").append(className).append(" (Orchestration principale)\n");
            if (hasDao) suggestedClasses.append(" - ").append(className.replace("Processor", "DAO").replace("Service", "DAO")).append(" (Accès données)\n");
            if (hasValidation) suggestedClasses.append(" - ").append(className.replace("Processor", "Validator")).append(" (Validation métier)\n");
            if (hasFileIo) suggestedClasses.append(" - ").append(className.replace("Processor", "FileWriter")).append(" (Génération I/O)\n");
            if (hasNotification) suggestedClasses.append(" - ").append(className.replace("Processor", "NotificationService")).append(" (Notifications)");

            findings.add(new Finding(
                    "APP-CODE-001-" + UUID.randomUUID().toString().substring(0, 8),
                    null,
                    null,
                    moduleName,
                    "APP-CODE-001",
                    "1.0",
                    Category.CODE,
                    Severity.HIGH,
                    90,
                    AutomationLevel.MANUAL_ONLY,
                    relativePath,
                    cid.getBegin().map(p -> p.line).orElse(1),
                    cid.getEnd().map(p -> p.line).orElse(1),
                    className,
                    desc,
                    "Violation du Single Responsibility Principle (SRP) identifiée dans la roadmap APP",
                    "Maintenance complexe, couplage fort entre persistance, règles métier et flux I/O",
                    suggestedClasses.toString(),
                    8.0,
                    "High",
                    FindingStatus.OPEN,
                    "class " + className + " { ... " + loc + " LOC }",
                    String.join(" -> ", responsibilities),
                    null
            ));
        }
    }

    /**
     * APP-CODE-002: Niveau d'abstraction dans les composants Spring Batch
     */
    private void checkAbstractionLevel(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        boolean isBatchComponent = cid.getImplementedTypes().stream()
                .anyMatch(t -> {
                    String name = t.getNameAsString();
                    String full = t.asString();
                    return name.equals("ItemProcessor") || name.equals("ItemReader") ||
                           name.equals("ItemWriter") || name.equals("Tasklet") ||
                           full.startsWith("ItemProcessor") || full.startsWith("ItemReader") ||
                           full.startsWith("ItemWriter") || full.startsWith("Tasklet");
                });

        if (!isBatchComponent) return;

        for (MethodDeclaration method : cid.getMethods()) {
            String mName = method.getNameAsString();
            if (mName.equals("process") || mName.equals("execute") || mName.equals("read") || mName.equals("write")) {
                int loc = method.getEnd().map(p -> p.line).orElse(0) - method.getBegin().map(p -> p.line).orElse(0);

                boolean hasSqlOrDao = method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> c.getNameAsString().matches(".*(find|save|update|delete|query|execute|select).*"));
                boolean hasMapping = method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(c -> c.getNameAsString().matches(".*(set|map|convert|dto|builder|get).*"));
                boolean hasCalculation = method.toString().contains("+") || method.toString().contains("*") || method.toString().contains("BigDecimal");

                if (loc >= 15 && hasSqlOrDao && (hasMapping || hasCalculation)) {
                    findings.add(new Finding(
                            "APP-CODE-002-" + UUID.randomUUID().toString().substring(0, 8),
                            null,
                            null,
                            moduleName,
                            "APP-CODE-002",
                            "1.0",
                            Category.CODE,
                            Severity.MEDIUM,
                            92,
                            AutomationLevel.REVIEW_REQUIRED,
                            relativePath,
                            method.getBegin().map(p -> p.line).orElse(1),
                            method.getEnd().map(p -> p.line).orElse(1),
                            cid.getNameAsString() + "." + mName,
                            "La méthode principale '" + mName + "' mélange plusieurs niveaux d'abstraction (orchestration, accès données, mapping et calculs).",
                            "La roadmap APP impose que la méthode principale du composant Batch permette de comprendre le déroulement global du traitement et délègue les détails à des méthodes secondaires de même niveau d'abstraction.",
                            "Compréhension difficile du flux de traitement batch",
                            "Extraire les calculs et mappings dans des méthodes privées dédiées pour conserver uniquement l'orchestration principale.",
                            3.0,
                            "Low",
                            FindingStatus.OPEN,
                            method.toString().substring(0, Math.min(method.toString().length(), 200)) + "...",
                            "orchestration -> [SQL/DAO, Mapping, Calculation]",
                            null
                    ));
                }
            }
        }
    }

    /**
     * Java 17 modernization recipes (JAVA17-001, JAVA17-002, JAVA17-004, JAVA17-005)
     */
    private void checkJava17Modernization(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        // JAVA17-002: java.io.File -> java.nio.file.Files / Path
        cid.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).forEach(creation -> {
            if (creation.getType().asString().equals("File")) {
                findings.add(new Finding(
                        "JAVA17-002-" + UUID.randomUUID().toString().substring(0, 8),
                        null, null, moduleName,
                        "JAVA17-002", "1.0",
                        Category.JAVA17, Severity.LOW, 95,
                        AutomationLevel.AUTO_SAFE,
                        relativePath,
                        creation.getBegin().map(p -> p.line).orElse(1),
                        creation.getEnd().map(p -> p.line).orElse(1),
                        creation.toString(),
                        "Usage de l'ancienne API java.io.File. Remplacer par java.nio.file.Path et Files.",
                        "L'API java.nio.file offre une meilleure gestion des erreurs et des performances supérieures.",
                        "Modernisation standard Java 17",
                        "Utiliser Path.of(...) ou Files.*",
                        0.5, "Low", FindingStatus.OPEN,
                        creation.toString(), null, null
                ));
            }
        });

        // JAVA17-001: Try-finally without resource -> try-with-resources
        cid.findAll(com.github.javaparser.ast.stmt.TryStmt.class).forEach(tryStmt -> {
            if (tryStmt.getResources().isEmpty() && tryStmt.getFinallyBlock().isPresent()) {
                String finallyBody = tryStmt.getFinallyBlock().get().toString();
                if (finallyBody.contains(".close()")) {
                    findings.add(new Finding(
                            "JAVA17-001-" + UUID.randomUUID().toString().substring(0, 8),
                            null, null, moduleName,
                            "JAVA17-001", "1.0",
                            Category.JAVA17, Severity.MEDIUM, 98,
                            AutomationLevel.AUTO_SAFE,
                            relativePath,
                            tryStmt.getBegin().map(p -> p.line).orElse(1),
                            tryStmt.getEnd().map(p -> p.line).orElse(1),
                            "try-finally",
                            "Fermeture manuelle de ressource dans un bloc finally. Remplacer par un try-with-resources.",
                            "Évite les fuites de descripteurs de fichiers ou connexions JDBC en cas d'exception.",
                            "Modernisation Java standard",
                            "Convertir en try (Resource r = ...) { ... }",
                            1.0, "Low", FindingStatus.OPEN,
                            tryStmt.toString().substring(0, Math.min(tryStmt.toString().length(), 150)) + "...",
                            null, null
                    ));
                }
            }
        });

        // JAVA17-003: Boucles imbriquées vers Stream API (LoopToStreamModernizationRecipe)
        cid.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class).forEach(outerLoop -> {
            if (outerLoop.getParentNode().flatMap(p -> p.findAncestor(com.github.javaparser.ast.stmt.ForEachStmt.class)).isPresent()) {
                return;
            }
            List<com.github.javaparser.ast.stmt.ForEachStmt> innerLoops = outerLoop.getBody().findAll(com.github.javaparser.ast.stmt.ForEachStmt.class);
            if (!innerLoops.isEmpty()) {
                com.github.javaparser.ast.stmt.ForEachStmt innerLoop = innerLoops.get(0);
                findings.add(new Finding(
                        "JAVA17-003-" + UUID.randomUUID().toString().substring(0, 8),
                        null, null, moduleName,
                        "JAVA17-003", "1.0",
                        Category.JAVA17, Severity.LOW, 95,
                        AutomationLevel.AUTO_SAFE,
                        relativePath,
                        outerLoop.getBegin().map(p -> p.line).orElse(1),
                        innerLoop.getEnd().map(p -> p.line).orElse(1),
                        "nested-for-loop",
                        "Boucle for imbriquée détectée. Remplacer par un pipeline Stream avec flatMap / filter / toList.",
                        "Les Streams réduisent la complexité cyclomatique et évitent les accumulateurs mutables.",
                        "Modernisation Java 17 Stream API",
                        "Convertir en collection.stream().flatMap(...).filter(...).toList()",
                        1.0, "Low", FindingStatus.OPEN,
                        outerLoop.toString().substring(0, Math.min(outerLoop.toString().length(), 150)) + "...",
                        null, null
                ));
            }
        });

        // JAVA17-004: Switch statement -> Switch expression
        cid.findAll(com.github.javaparser.ast.stmt.SwitchStmt.class).forEach(switchStmt -> {
            findings.add(new Finding(
                    "JAVA17-004-" + UUID.randomUUID().toString().substring(0, 8),
                    null, null, moduleName,
                    "JAVA17-004", "1.0",
                    Category.JAVA17, Severity.LOW, 90,
                    AutomationLevel.AUTO_SAFE,
                    relativePath,
                    switchStmt.getBegin().map(p -> p.line).orElse(1),
                    switchStmt.getEnd().map(p -> p.line).orElse(1),
                    "switch-statement",
                    "Switch statement verbeux détecté. Remplacer par une switch expression Java 17 (syntaxe ->).",
                    "Les switch expressions Java 17 garantissent l'exhaustivité, évitent les oublis de 'break' (fall-through involontaire) et réduisent la verbosité.",
                    "Modernisation standard Java 17",
                    "Remplacer par switch (...) { case X -> ...; }",
                    0.5, "Low", FindingStatus.OPEN,
                    switchStmt.toString().substring(0, Math.min(switchStmt.toString().length(), 150)) + "...",
                    null, null
            ));
        });
    }

    /**
     * APP-BATCH-001 & APP-BATCH-002: Modernisation Spring Batch 5 (JobBuilderFactory & StepBuilderFactory)
     */
    private void checkBatchModernization(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        cid.findAll(FieldDeclaration.class).stream()
                .filter(f -> f.getElementType().asString().contains("JobBuilderFactory"))
                .forEach(field -> {
                    findings.add(new Finding(
                            "APP-BATCH-001-" + UUID.randomUUID().toString().substring(0, 8),
                            null, null, moduleName,
                            "APP-BATCH-001", "1.0",
                            Category.FRAMEWORK, Severity.HIGH, 98,
                            AutomationLevel.AUTO_SAFE,
                            relativePath,
                            field.getBegin().map(p -> p.line).orElse(1),
                            field.getEnd().map(p -> p.line).orElse(1),
                            field.toString().trim(),
                            "Usage de 'JobBuilderFactory' déprécié dans Spring Batch 5. Remplacer par 'new JobBuilder(name, jobRepository)'.",
                            "Spring Batch 5 a supprimé les factories d'injection en faveur de constructeurs directs avec JobRepository explicite.",
                            "Incompatibilité Spring Batch 5",
                            "Instancier directement new JobBuilder(name, jobRepository) et injecter JobRepository",
                            2.0, "Medium", FindingStatus.OPEN,
                            field.toString().trim(), null, null
                    ));
                });

        cid.findAll(FieldDeclaration.class).stream()
                .filter(f -> f.getElementType().asString().contains("StepBuilderFactory"))
                .forEach(field -> {
                    findings.add(new Finding(
                            "APP-BATCH-002-" + UUID.randomUUID().toString().substring(0, 8),
                            null, null, moduleName,
                            "APP-BATCH-002", "1.0",
                            Category.FRAMEWORK, Severity.HIGH, 98,
                            AutomationLevel.AUTO_SAFE,
                            relativePath,
                            field.getBegin().map(p -> p.line).orElse(1),
                            field.getEnd().map(p -> p.line).orElse(1),
                            field.toString().trim(),
                            "Usage de 'StepBuilderFactory' déprécié dans Spring Batch 5. Remplacer par 'new StepBuilder(name, jobRepository)'.",
                            "Spring Batch 5 requiert un JobRepository explicite et un PlatformTransactionManager lors de la construction des steps.",
                            "Incompatibilité Spring Batch 5",
                            "Instancier directement new StepBuilder(name, jobRepository) et spécifier transactionManager",
                            2.0, "Medium", FindingStatus.OPEN,
                            field.toString().trim(), null, null
                    ));
                });
    }

    /**
     * JAVA17-007: Null Safety & Optional rules
     */
    private void checkNullSafetyAndOptional(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        boolean isJpaEntity = cid.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Entity") || a.getNameAsString().equals("Table"));

        if (isJpaEntity) {
            for (FieldDeclaration field : cid.getFields()) {
                if (field.getElementType().asString().startsWith("Optional")) {
                    findings.add(new Finding(
                            "JAVA17-007-" + UUID.randomUUID().toString().substring(0, 8),
                            null, null, moduleName,
                            "JAVA17-007", "1.0",
                            Category.JAVA17, Severity.HIGH, 99,
                            AutomationLevel.REVIEW_REQUIRED,
                            relativePath,
                            field.getBegin().map(p -> p.line).orElse(1),
                            field.getEnd().map(p -> p.line).orElse(1),
                            field.toString().trim(),
                            "Utilisation de Optional comme champ d'entité JPA.",
                            "Optional n'est pas sérialisable par défaut et n'est pas supporté comme attribut persistant JPA (interdit par la roadmap APP).",
                            "Risque d'erreur de mapping Hibernate / JPA",
                            "Remplacer par un champ nullable direct et utiliser Optional uniquement aux frontières des méthodes de service ou getters.",
                            1.0, "High", FindingStatus.OPEN,
                            field.toString().trim(), null, null
                    ));
                }
            }
        }
    }
}
