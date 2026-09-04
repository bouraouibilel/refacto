package com.refacto.migration.discovery;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import com.refacto.migration.core.model.BatchDescriptor;
import com.refacto.migration.core.model.BatchStepDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Découverte approfondie des configurations et jobs Spring Batch (Section 10 du cahier des charges).
 * Identifie les Jobs, Steps, Readers, Processors, Writers, Tasklets et listeners
 * configurés en Java (@EnableBatchProcessing, @Bean Job, Tasklets) ou en XML (<batch:job>, <job>).
 */
public class SpringBatchDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SpringBatchDiscoveryService.class);

    private static final Pattern XML_JOB_PATTERN = Pattern.compile("<(?:batch:)?job\\s+[^>]*id=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_STEP_PATTERN = Pattern.compile("<(?:batch:)?step\\s+[^>]*id=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_TASKLET_PATTERN = Pattern.compile("<(?:batch:)?tasklet\\s+[^>]*ref=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_LISTENER_PATTERN = Pattern.compile("<(?:batch:)?listener\\s+[^>]*ref=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    public List<BatchDescriptor> discoverBatches(Path projectRoot, String moduleName, Path moduleDir) {
        List<BatchDescriptor> batches = new ArrayList<>();
        Set<String> discoveredJobKeys = new HashSet<>();

        boolean isBatchModule = moduleName != null && (
                moduleName.toLowerCase().contains("batch") ||
                moduleDir.getFileName().toString().toLowerCase().contains("batch")
        );

        // 1. Découverte dans le code Java (src/main/java)
        Path srcMain = moduleDir.resolve("src/main/java");
        if (Files.exists(srcMain)) {
            try (Stream<Path> stream = Files.walk(srcMain)) {
                stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                        .forEach(javaFile -> {
                            try {
                                analyzeJavaFile(javaFile, moduleName, projectRoot, batches, discoveredJobKeys, isBatchModule);
                            } catch (Exception e) {
                                log.debug("Impossible d'analyser {} pour Spring Batch: {}", javaFile, e.getMessage());
                            }
                        });
            } catch (Exception e) {
                log.warn("Erreur parcours Java dans {} : {}", srcMain, e.getMessage());
            }
        }

        // 2. Découverte dans les configurations XML (src/main/resources, src/main/webapp, etc.)
        scanXmlConfigurations(moduleDir, moduleName, projectRoot, batches, discoveredJobKeys);

        return batches;
    }

    private void analyzeJavaFile(Path javaFile, String moduleName, Path projectRoot,
                                 List<BatchDescriptor> batches, Set<String> discoveredJobKeys,
                                 boolean isBatchModule) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        final Path absProjectRoot = projectRoot.toAbsolutePath().normalize();
        final Path absJavaFile = javaFile.toAbsolutePath().normalize();
        String relativePath = absProjectRoot.relativize(absJavaFile).toString().replace('\\', '/');

        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = cid.getNameAsString();
            String fqcn = cid.getFullyQualifiedName().orElse(className);

            boolean implementsJobOrTasklet = cid.getImplementedTypes().stream()
                    .anyMatch(t -> {
                        String typeName = t.getNameAsString();
                        return typeName.equals("Tasklet") || typeName.equals("Job");
                    });

            boolean isJobOrTaskletClass = !cid.isInterface() &&
                    !className.endsWith("Config") &&
                    !className.endsWith("Configuration") &&
                    !className.endsWith("Reader") &&
                    !className.endsWith("Writer") &&
                    !className.endsWith("Processor") &&
                    !className.endsWith("Validator") &&
                    !className.endsWith("Service") &&
                    !className.endsWith("Listener") &&
                    !className.endsWith("Test") && (
                        className.endsWith("Job") ||
                        className.endsWith("Batch") ||
                        className.endsWith("Tasklet") ||
                        className.endsWith("Launcher")
                    );

            // A. Méthodes retournant Job
            boolean foundJobMethod = false;
            for (MethodDeclaration method : cid.getMethods()) {
                Type returnType = method.getType();
                if (returnType.asString().endsWith("Job")) {
                    foundJobMethod = true;
                    String jobName = method.getNameAsString();
                    if (discoveredJobKeys.add(moduleName + ":" + jobName)) {
                        List<BatchStepDescriptor> steps = extractStepsFromJob(method, cid);
                        List<String> listeners = extractListeners(method);
                        String cron = extractSchedulingCron(cid);

                        batches.add(new BatchDescriptor(
                                jobName,
                                fqcn,
                                relativePath,
                                moduleName,
                                steps,
                                listeners,
                                cron
                        ));
                    }
                }
            }

            // B. Détection de classe Tasklet ou Batch dédiée (si aucun @Bean Job explicite)
            if (!foundJobMethod && (implementsJobOrTasklet || isJobOrTaskletClass)) {
                String jobName = className;
                if (discoveredJobKeys.add(moduleName + ":" + jobName)) {
                    List<BatchStepDescriptor> steps = new ArrayList<>();
                    String stepType = implementsJobOrTasklet ? "TASKLET" : "CHUNK";
                    steps.add(new BatchStepDescriptor(className + "Step", stepType, null, null, null, className, null));
                    String cron = extractSchedulingCron(cid);

                    batches.add(new BatchDescriptor(
                            jobName,
                            fqcn,
                            relativePath,
                            moduleName,
                            steps,
                            Collections.emptyList(),
                            cron
                    ));
                }
            }
        }
    }

    private void scanXmlConfigurations(Path moduleDir, String moduleName, Path projectRoot,
                                      List<BatchDescriptor> batches, Set<String> discoveredJobKeys) {
        Path resDir = moduleDir.resolve("src/main/resources");
        if (!Files.exists(resDir)) {
            resDir = moduleDir;
        }

        try (Stream<Path> stream = Files.walk(resDir)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".xml"))
                    .forEach(xmlFile -> {
                        try {
                            analyzeXmlFile(xmlFile, moduleName, projectRoot, batches, discoveredJobKeys);
                        } catch (Exception e) {
                            log.debug("Erreur analyse XML {} : {}", xmlFile, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.debug("Erreur parcours XML dans {} : {}", resDir, e.getMessage());
        }
    }

    private void analyzeXmlFile(Path xmlFile, String moduleName, Path projectRoot,
                                List<BatchDescriptor> batches, Set<String> discoveredJobKeys) throws Exception {
        String content = Files.readString(xmlFile);
        if (!content.contains("job") && !content.contains("batch")) {
            return;
        }

        Matcher jobMatcher = XML_JOB_PATTERN.matcher(content);
        final Path absProjectRoot = projectRoot.toAbsolutePath().normalize();
        final Path absXmlFile = xmlFile.toAbsolutePath().normalize();
        String relativePath = absProjectRoot.relativize(absXmlFile).toString().replace('\\', '/');

        while (jobMatcher.find()) {
            String jobId = jobMatcher.group(1);
            if (discoveredJobKeys.add(moduleName + ":" + jobId)) {
                List<BatchStepDescriptor> steps = new ArrayList<>();
                Matcher stepMatcher = XML_STEP_PATTERN.matcher(content);
                while (stepMatcher.find()) {
                    String stepId = stepMatcher.group(1);
                    steps.add(new BatchStepDescriptor(stepId, "CHUNK", null, null, null, null, 10));
                }

                if (steps.isEmpty()) {
                    Matcher taskletMatcher = XML_TASKLET_PATTERN.matcher(content);
                    if (taskletMatcher.find()) {
                        String taskletRef = taskletMatcher.group(1);
                        steps.add(new BatchStepDescriptor(jobId + "Step", "TASKLET", null, null, null, taskletRef, null));
                    }
                }

                List<String> listeners = new ArrayList<>();
                Matcher listenerMatcher = XML_LISTENER_PATTERN.matcher(content);
                while (listenerMatcher.find()) {
                    listeners.add(listenerMatcher.group(1));
                }

                batches.add(new BatchDescriptor(
                        jobId,
                        "Spring XML: " + xmlFile.getFileName(),
                        relativePath,
                        moduleName,
                        steps,
                        listeners,
                        null
                ));
            }
        }
    }

    private List<BatchStepDescriptor> extractStepsFromJob(MethodDeclaration jobMethod, ClassOrInterfaceDeclaration classDecl) {
        List<BatchStepDescriptor> steps = new ArrayList<>();

        List<MethodCallExpr> calls = jobMethod.findAll(MethodCallExpr.class);
        for (MethodCallExpr call : calls) {
            String name = call.getNameAsString();
            if (name.equals("start") || name.equals("next") || name.equals("flow")) {
                call.getArguments().forEach(arg -> {
                    String stepRef = arg.toString();
                    if (stepRef.endsWith("()")) {
                        stepRef = stepRef.substring(0, stepRef.length() - 2);
                    }
                    BatchStepDescriptor stepDesc = findStepDeclaration(stepRef, classDecl);
                    if (stepDesc != null) {
                        steps.add(stepDesc);
                    } else {
                        steps.add(new BatchStepDescriptor(stepRef, "UNKNOWN", null, null, null, null, null));
                    }
                });
            }
        }

        if (steps.isEmpty()) {
            for (MethodDeclaration m : classDecl.getMethods()) {
                if (m.getType().asString().endsWith("Step")) {
                    steps.add(analyzeStepMethod(m));
                }
            }
        }

        return steps;
    }

    private BatchStepDescriptor findStepDeclaration(String stepMethodName, ClassOrInterfaceDeclaration classDecl) {
        for (MethodDeclaration m : classDecl.getMethods()) {
            if (m.getNameAsString().equals(stepMethodName) && m.getType().asString().endsWith("Step")) {
                return analyzeStepMethod(m);
            }
        }
        return null;
    }

    private BatchStepDescriptor analyzeStepMethod(MethodDeclaration stepMethod) {
        String stepName = stepMethod.getNameAsString();
        String reader = null;
        String processor = null;
        String writer = null;
        String tasklet = null;
        Integer chunkSize = null;
        String stepType = "CHUNK";

        for (MethodCallExpr call : stepMethod.findAll(MethodCallExpr.class)) {
            String methodName = call.getNameAsString();
            if (methodName.equals("chunk")) {
                stepType = "CHUNK";
                if (!call.getArguments().isEmpty()) {
                    try {
                        chunkSize = Integer.parseInt(call.getArgument(0).toString());
                    } catch (Exception ignored) {}
                }
            } else if (methodName.equals("tasklet")) {
                stepType = "TASKLET";
                if (!call.getArguments().isEmpty()) {
                    tasklet = call.getArgument(0).toString();
                }
            } else if (methodName.equals("reader") && !call.getArguments().isEmpty()) {
                reader = call.getArgument(0).toString();
            } else if (methodName.equals("processor") && !call.getArguments().isEmpty()) {
                processor = call.getArgument(0).toString();
            } else if (methodName.equals("writer") && !call.getArguments().isEmpty()) {
                writer = call.getArgument(0).toString();
            }
        }

        return new BatchStepDescriptor(stepName, stepType, reader, processor, writer, tasklet, chunkSize);
    }

    private List<String> extractListeners(MethodDeclaration method) {
        List<String> listeners = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("listener") && !call.getArguments().isEmpty()) {
                listeners.add(call.getArgument(0).toString());
            }
        }
        return listeners;
    }

    private String extractSchedulingCron(ClassOrInterfaceDeclaration classDecl) {
        for (MethodDeclaration m : classDecl.getMethods()) {
            for (AnnotationExpr a : m.getAnnotations()) {
                if (a.getNameAsString().equals("Scheduled")) {
                    return a.toString();
                }
            }
        }
        return null;
    }
}
