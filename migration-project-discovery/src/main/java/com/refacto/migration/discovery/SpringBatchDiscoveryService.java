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
import java.util.stream.Stream;

/**
 * Découverte approfondie des configurations et jobs Spring Batch (Section 10 du cahier des charges).
 * Identifie les Jobs, Steps, Readers, Processors, Writers, Tasklets et listeners.
 */
public class SpringBatchDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SpringBatchDiscoveryService.class);

    public List<BatchDescriptor> discoverBatches(Path projectRoot, String moduleName, Path moduleDir) {
        List<BatchDescriptor> batches = new ArrayList<>();
        Path srcMain = moduleDir.resolve("src/main/java");
        if (!Files.exists(srcMain)) {
            return batches;
        }

        try (Stream<Path> stream = Files.walk(srcMain)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .forEach(javaFile -> {
                        try {
                            analyzeJavaFile(javaFile, moduleName, projectRoot, batches);
                        } catch (Exception e) {
                            log.debug("Impossible d'analyser {} pour Spring Batch: {}", javaFile, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Erreur parcours Java dans {} : {}", srcMain, e.getMessage());
        }

        return batches;
    }

    private void analyzeJavaFile(Path javaFile, String moduleName, Path projectRoot, List<BatchDescriptor> batches) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);

        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean isBatchConfig = cid.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("EnableBatchProcessing") ||
                                   a.getNameAsString().equals("Configuration"));

            // Check methods returning Job
            for (MethodDeclaration method : cid.getMethods()) {
                Type returnType = method.getType();
                if (returnType.asString().endsWith("Job")) {
                    String jobName = method.getNameAsString();
                    List<BatchStepDescriptor> steps = extractStepsFromJob(method, cid);
                    List<String> listeners = extractListeners(method);
                    String cron = extractSchedulingCron(cid);

                    String relativePath = projectRoot.relativize(javaFile).toString().replace('\\', '/');

                    BatchDescriptor descriptor = new BatchDescriptor(
                            jobName,
                            cid.getFullyQualifiedName().orElse(cid.getNameAsString()),
                            relativePath,
                            moduleName,
                            steps,
                            listeners,
                            cron
                    );
                    batches.add(descriptor);
                }
            }
        }
    }

    private List<BatchStepDescriptor> extractStepsFromJob(MethodDeclaration jobMethod, ClassOrInterfaceDeclaration classDecl) {
        List<BatchStepDescriptor> steps = new ArrayList<>();

        // Find calls like .flow(step1()).next(step2()) or .start(step1()).next(step2())
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
            // Default step based on convention if methods returning Step exist
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
