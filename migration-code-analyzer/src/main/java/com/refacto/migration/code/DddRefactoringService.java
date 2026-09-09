package com.refacto.migration.code;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.refacto.migration.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service d'analyse et de refactoring architectural DDD (Domain-Driven Design).
 * Identifie les Bounded Contexts, classifie les composants en couches (Domain / Application / Infrastructure / Commons)
 * et génère la composition multi-modules cible pour décomposer et simplifier l'application.
 */
public class DddRefactoringService {

    private static final Logger log = LoggerFactory.getLogger(DddRefactoringService.class);

    private static final Set<String> TECHNICAL_KEYWORDS = Set.of(
            "util", "helper", "string", "date", "csv", "xml", "json", "hash", "encrypt", "io", "file", "converter"
    );

    public DddRefactoringPlan buildRefactoringPlan(
            Path projectRoot,
            List<ModuleDescriptor> modules,
            List<BatchDescriptor> batches,
            List<ArchitectureCoupling> couplings
    ) {
        log.info("Génération du plan de refactoring architectural DDD...");

        // 1. Détection des Bounded Contexts à partir des domaines identifiés
        List<DddBoundedContext> contexts = detectBoundedContexts(modules, batches, couplings);

        // 2. Inventaire et classification des classes par couche DDD et Bounded Context
        List<DddComponentClassification> classifications = classifyProjectClasses(projectRoot, modules, contexts, couplings);

        // 3. Modélisation de la composition modulaire Maven cible
        List<DddModuleStructure> targetModules = buildTargetModuleStructure(contexts, classifications);

        // 4. Calcul des métriques de découplage
        int decoupledEntities = (int) couplings.stream().filter(c -> !c.eligibleForCommons()).count();
        int isolatedModules = (int) contexts.stream().filter(c -> !c.id().equals("app-commons")).count();

        String summary = String.format(
                "Décomposition DDD en %d Bounded Contexts (%s) avec isolation stricte en couches Domain, Application et Infrastructure. " +
                "%d composants métier partagés sont décorrélés au profit d'échanges par identifiants ou DTOs.",
                contexts.size(),
                contexts.stream().map(DddBoundedContext::name).collect(Collectors.joining(", ")),
                decoupledEntities
        );

        return new DddRefactoringPlan(
                contexts,
                classifications,
                targetModules,
                summary,
                decoupledEntities,
                isolatedModules
        );
    }

    private List<DddBoundedContext> detectBoundedContexts(
            List<ModuleDescriptor> modules,
            List<BatchDescriptor> batches,
            List<ArchitectureCoupling> couplings
    ) {
        List<DddBoundedContext> contexts = new ArrayList<>();
        Set<String> identifiedDomains = new LinkedHashSet<>();

        // Détection à partir des batchs
        for (BatchDescriptor batch : batches) {
            String domain = extractDomainFromBatch(batch.jobName());
            if (domain != null) identifiedDomains.add(domain);
        }

        // Détection à partir des modules
        for (ModuleDescriptor module : modules) {
            String domain = extractDomainFromModule(module.artifactId());
            if (domain != null) identifiedDomains.add(domain);
        }

        // Détection à partir des entités ou DAO partagés
        for (ArchitectureCoupling coupling : couplings) {
            if (!coupling.eligibleForCommons()) {
                String domain = extractDomainFromClass(coupling.componentName());
                if (domain != null) identifiedDomains.add(domain);
            }
        }

        // Si aucun domaine spécifique n'a été extrait, par défaut créer le contexte Core
        if (identifiedDomains.isEmpty()) {
            identifiedDomains.add("core");
        }

        for (String domain : identifiedDomains) {
            String contextId = domain + "-context";
            String name = "Contexte " + capitalize(domain);
            String description = "Contexte délimité (Bounded Context) regroupant les règles métier et traitements du domaine " + domain + ".";

            List<String> srcModules = modules.stream()
                    .map(ModuleDescriptor::artifactId)
                    .filter(m -> m.toLowerCase().contains(domain.toLowerCase()))
                    .collect(Collectors.toList());

            List<String> batchJobs = batches.stream()
                    .map(BatchDescriptor::jobName)
                    .filter(j -> j.toLowerCase().contains(domain.toLowerCase()))
                    .collect(Collectors.toList());

            List<String> mainEntities = couplings.stream()
                    .map(ArchitectureCoupling::componentName)
                    .filter(c -> c.toLowerCase().contains(domain.toLowerCase()))
                    .collect(Collectors.toList());

            contexts.add(new DddBoundedContext(
                    contextId,
                    name,
                    description,
                    srcModules,
                    mainEntities,
                    batchJobs,
                    "com.sample." + domain
            ));
        }

        // Contexte transverse technique systématique (APP-commons)
        contexts.add(new DddBoundedContext(
                "app-commons",
                "Bibliothèque Transverse (APP-commons)",
                "Utilitaires techniques mutualisés et fonctions d'infrastructure transverses agnostiques de tout domaine métier.",
                List.of("legacy-common"),
                Collections.emptyList(),
                Collections.emptyList(),
                "com.sample.commons"
        ));

        return contexts;
    }

    private List<DddComponentClassification> classifyProjectClasses(
            Path projectRoot,
            List<ModuleDescriptor> modules,
            List<DddBoundedContext> contexts,
            List<ArchitectureCoupling> couplings
    ) {
        List<DddComponentClassification> classifications = new ArrayList<>();
        Set<String> commonsEligibleClasses = couplings.stream()
                .filter(ArchitectureCoupling::eligibleForCommons)
                .map(ArchitectureCoupling::componentName)
                .collect(Collectors.toSet());

        for (ModuleDescriptor module : modules) {
            Path moduleDir = projectRoot.resolve(module.relativePath());
            Path srcMain = moduleDir.resolve("src/main/java");
            if (!Files.exists(srcMain)) continue;

            try (Stream<Path> stream = Files.walk(srcMain)) {
                stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                        .forEach(javaFile -> {
                            try {
                                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                                String packageName = cu.getPackageDeclaration()
                                        .map(pd -> pd.getName().asString())
                                        .orElse("default");

                                for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                                    String className = cid.getNameAsString();
                                    DddComponentClassification classification = classifyClass(
                                            className,
                                            packageName,
                                            module.artifactId(),
                                            cid,
                                            contexts,
                                            commonsEligibleClasses
                                    );
                                    classifications.add(classification);
                                }
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }

        return classifications;
    }

    private DddComponentClassification classifyClass(
            String className,
            String currentPackage,
            String currentModule,
            ClassOrInterfaceDeclaration cid,
            List<DddBoundedContext> contexts,
            Set<String> commonsEligibleClasses
    ) {
        DddLayer layer;
        String rationale;
        String assignedContextId;
        String targetModule;
        String targetPackage;

        boolean isTechnical = commonsEligibleClasses.contains(className) ||
                TECHNICAL_KEYWORDS.stream().anyMatch(k -> className.toLowerCase().contains(k));

        if (isTechnical) {
            layer = DddLayer.COMMONS;
            assignedContextId = "app-commons";
            targetModule = "app-commons";
            targetPackage = "com.sample.commons.util";
            rationale = "Composant technique pur sans logique métier. Déplacer dans la librairie mutualisée 'app-commons'.";
        } else if (className.contains("Reader") || className.contains("Writer") || className.contains("Config") || className.contains("Job") || className.contains("Step")) {
            layer = DddLayer.INFRASTRUCTURE;
            assignedContextId = resolveContextForClass(className, currentModule, contexts);
            targetModule = assignedContextId.replace("-context", "") + "-infrastructure";
            targetPackage = "com.sample." + assignedContextId.replace("-context", "") + ".infrastructure.batch";
            rationale = "Composant d'infrastructure Spring Batch dédié aux entrées/sorties et à la configuration.";
        } else if (className.contains("DAO") || className.contains("Repository") || (className.contains("Entity") && hasJpaAnnotations(cid))) {
            layer = DddLayer.INFRASTRUCTURE;
            assignedContextId = resolveContextForClass(className, currentModule, contexts);
            targetModule = assignedContextId.replace("-context", "") + "-infrastructure";
            targetPackage = "com.sample." + assignedContextId.replace("-context", "") + ".infrastructure.persistence";
            rationale = "Composant de persistance ou mapping ORM/JPA. Isoler dans la couche infrastructure pour ne pas contaminer le domaine.";
        } else if (className.contains("Processor") || className.contains("Service") || className.contains("Validator") || className.contains("DTO")) {
            layer = DddLayer.APPLICATION;
            assignedContextId = resolveContextForClass(className, currentModule, contexts);
            targetModule = assignedContextId.replace("-context", "") + "-application";
            targetPackage = "com.sample." + assignedContextId.replace("-context", "") + ".application";
            rationale = "Service ou processeur d'orchestration applicatif. Coordonne les cas d'usage sans accès direct aux ressources externes.";
        } else {
            // Par défaut entité ou value object métier pur
            layer = DddLayer.DOMAIN;
            assignedContextId = resolveContextForClass(className, currentModule, contexts);
            targetModule = assignedContextId.replace("-context", "") + "-domain";
            targetPackage = "com.sample." + assignedContextId.replace("-context", "") + ".domain.model";
            rationale = "Modèle de données ou agrégat métier pur. Doit rester sans dépendance externe ni framework de persistance.";
        }

        return new DddComponentClassification(
                className,
                currentModule,
                currentPackage,
                layer,
                assignedContextId,
                targetModule,
                targetPackage,
                rationale
        );
    }

    private String resolveContextForClass(String className, String currentModule, List<DddBoundedContext> contexts) {
        String lower = (className + " " + currentModule).toLowerCase();
        for (DddBoundedContext ctx : contexts) {
            String domain = ctx.id().replace("-context", "").toLowerCase();
            if (!domain.equals("app-commons") && lower.contains(domain)) {
                return ctx.id();
            }
        }
        // Contexte premier trouvé
        return contexts.stream()
                .filter(c -> !c.id().equals("app-commons"))
                .findFirst()
                .map(DddBoundedContext::id)
                .orElse("core-context");
    }

    private List<DddModuleStructure> buildTargetModuleStructure(
            List<DddBoundedContext> contexts,
            List<DddComponentClassification> classifications
    ) {
        List<DddModuleStructure> structures = new ArrayList<>();

        for (DddBoundedContext ctx : contexts) {
            if (ctx.id().equals("app-commons")) {
                List<String> assigned = classifications.stream()
                        .filter(c -> c.targetBoundedContext().equals("app-commons"))
                        .map(DddComponentClassification::className)
                        .toList();

                structures.add(new DddModuleStructure(
                        "app-commons",
                        "app-commons",
                        DddLayer.COMMONS,
                        "Module partagé technique transverse contenant utilitaires et composants agnostiques.",
                        Collections.emptyList(),
                        assigned
                ));
                continue;
            }

            String prefix = ctx.id().replace("-context", "");

            // 1. Module Domain
            List<String> domainClasses = classifications.stream()
                    .filter(c -> c.targetBoundedContext().equals(ctx.id()) && c.layer() == DddLayer.DOMAIN)
                    .map(DddComponentClassification::className)
                    .toList();
            structures.add(new DddModuleStructure(
                    prefix + "-domain",
                    ctx.id(),
                    DddLayer.DOMAIN,
                    "Modèle métier pur, agrégats et interfaces de repositories du domaine " + prefix + " (zéro dépendance framework).",
                    Collections.emptyList(),
                    domainClasses
            ));

            // 2. Module Application
            List<String> appClasses = classifications.stream()
                    .filter(c -> c.targetBoundedContext().equals(ctx.id()) && c.layer() == DddLayer.APPLICATION)
                    .map(DddComponentClassification::className)
                    .toList();
            structures.add(new DddModuleStructure(
                    prefix + "-application",
                    ctx.id(),
                    DddLayer.APPLICATION,
                    "Cas d'usages et orchestration métier pour le domaine " + prefix + ".",
                    List.of(prefix + "-domain"),
                    appClasses
            ));

            // 3. Module Infrastructure
            List<String> infraClasses = classifications.stream()
                    .filter(c -> c.targetBoundedContext().equals(ctx.id()) && c.layer() == DddLayer.INFRASTRUCTURE)
                    .map(DddComponentClassification::className)
                    .toList();
            structures.add(new DddModuleStructure(
                    prefix + "-infrastructure",
                    ctx.id(),
                    DddLayer.INFRASTRUCTURE,
                    "Adaptateurs Spring Batch 5, persistence JPA, DAOs et clients externes du domaine " + prefix + ".",
                    List.of(prefix + "-domain", prefix + "-application", "app-commons", "spring-batch-core"),
                    infraClasses
            ));
        }

        return structures;
    }

    private boolean hasJpaAnnotations(ClassOrInterfaceDeclaration cid) {
        return cid.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Entity") || a.getNameAsString().equals("Table"));
    }

    private String extractDomainFromBatch(String jobName) {
        String clean = jobName.replace("Job", "").replace("job", "").toLowerCase();
        return clean.isBlank() ? null : clean;
    }

    private String extractDomainFromModule(String artifactId) {
        String clean = artifactId.replace("batch-", "").replace("-batch", "")
                .replace("-model", "").replace("-service", "")
                .replace("legacy-", "").toLowerCase();
        if (clean.equals("common") || clean.equals("commons") || clean.isBlank()) return null;
        return clean;
    }

    private String extractDomainFromClass(String className) {
        String clean = className.replace("DAO", "").replace("Repository", "")
                .replace("Entity", "").replace("Service", "")
                .replace("Shared", "").toLowerCase();
        return clean.isBlank() ? null : clean;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
