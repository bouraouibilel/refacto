package com.refacto.migration.transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.refacto.migration.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Moteur de transformation de code source basé sur AST JavaParser pour les recipes déterministes.
 */
public class AstTransformerService {

    private static final Logger log = LoggerFactory.getLogger(AstTransformerService.class);

    static {
        try {
            StaticJavaParser.getParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
        } catch (Exception ignored) {}
    }

    public Optional<String> transformCode(String originalContent, Finding finding) {
        if (originalContent == null || originalContent.isBlank()) {
            return Optional.empty();
        }

        try {
            CompilationUnit cu = StaticJavaParser.parse(originalContent);
            boolean lexicalSetupOk = false;
            try {
                LexicalPreservingPrinter.setup(cu);
                lexicalSetupOk = true;
            } catch (Exception e) {
                log.debug("LexicalPreservingPrinter setup skipped: {}", e.getMessage());
            }

            boolean modified = false;
            String recipeId = finding.recipeId() != null ? finding.recipeId() : "";

            if (recipeId.startsWith("FRAMEWORK-001")) {
                // javax.persistence.* -> jakarta.persistence.*
                modified = transformJavaxToJakarta(cu);
            } else if (recipeId.startsWith("FRAMEWORK-003") || recipeId.startsWith("FRAMEWORK-004")) {
                // JUnit 4 -> JUnit 5 annotations
                modified = transformJUnit4To5(cu);
            } else if (recipeId.startsWith("APP-BATCH-001") || recipeId.startsWith("APP-BATCH-002") || recipeId.startsWith("FRAMEWORK-002")) {
                // Spring Batch 5 JobBuilder & StepBuilder modernization
                modified = transformBatch5Builders(cu);
            } else if (recipeId.startsWith("JAVA17-001")) {
                // Try-with-resources conversion
                modified = transformTryWithResources(cu);
            } else if (recipeId.startsWith("JAVA17-002")) {
                // java.io.File -> java.nio.file.Path / Files
                modified = transformFileToPath(cu);
            } else if (recipeId.startsWith("JAVA17-003")) {
                // Nested loops to Stream API
                modified = transformNestedLoopsToStream(cu);
            } else if (recipeId.startsWith("JAVA17-004")) {
                // Switch expressions Java 17
                modified = transformSwitchExpressions(cu);
            } else if (recipeId.startsWith("APP-DB-005")) {
                // Suggest explicit projection comment/marker
                modified = annotateWithProjectionRecommendation(cu);
            }

            if (modified) {
                if (lexicalSetupOk) {
                    try {
                        return Optional.of(LexicalPreservingPrinter.print(cu));
                    } catch (Exception e) {
                        log.debug("LexicalPreservingPrinter print fallback: {}", e.getMessage());
                    }
                }
                return Optional.of(cu.toString());
            }
        } catch (Exception e) {
            log.debug("Impossible de transformer avec JavaParser {}: {}", finding.filePath(), e.getMessage());
            // Fallback: direct deterministic string replacement for safe packages
            if (finding.recipeId() != null && finding.recipeId().startsWith("FRAMEWORK-001") && originalContent.contains("javax.persistence")) {
                return Optional.of(originalContent.replace("javax.persistence", "jakarta.persistence"));
            }
        }

        return Optional.empty();
    }

    private boolean transformJavaxToJakarta(CompilationUnit cu) {
        boolean modified = false;
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (name.startsWith("javax.persistence")) {
                imp.setName(name.replace("javax.persistence", "jakarta.persistence"));
                modified = true;
            }
        }
        return modified;
    }

    private boolean transformJUnit4To5(CompilationUnit cu) {
        boolean modified = false;
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (name.equals("org.junit.Test")) {
                imp.setName("org.junit.jupiter.api.Test");
                modified = true;
            } else if (name.equals("org.junit.Before")) {
                imp.setName("org.junit.jupiter.api.BeforeEach");
                modified = true;
            } else if (name.equals("org.junit.After")) {
                imp.setName("org.junit.jupiter.api.AfterEach");
                modified = true;
            } else if (name.equals("org.junit.Ignore")) {
                imp.setName("org.junit.jupiter.api.Disabled");
                modified = true;
            }
        }

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            for (AnnotationExpr annotation : method.getAnnotations()) {
                if (annotation.getNameAsString().equals("Before")) {
                    annotation.replace(new MarkerAnnotationExpr("BeforeEach"));
                    modified = true;
                } else if (annotation.getNameAsString().equals("After")) {
                    annotation.replace(new MarkerAnnotationExpr("AfterEach"));
                    modified = true;
                } else if (annotation.getNameAsString().equals("Ignore")) {
                    annotation.replace(new MarkerAnnotationExpr("Disabled"));
                    modified = true;
                }
            }
        }

        return modified;
    }

    private boolean transformTryWithResources(CompilationUnit cu) {
        boolean modified = false;
        for (TryStmt tryStmt : cu.findAll(TryStmt.class)) {
            if (!tryStmt.getResources().isEmpty() || tryStmt.getFinallyBlock().isEmpty()) {
                continue;
            }
            BlockStmt finallyBlock = tryStmt.getFinallyBlock().get();
            List<MethodCallExpr> closeCalls = finallyBlock.findAll(MethodCallExpr.class).stream()
                    .filter(mc -> mc.getNameAsString().equals("close") && mc.getArguments().isEmpty())
                    .toList();
            if (closeCalls.isEmpty()) {
                continue;
            }

            for (MethodCallExpr closeCall : closeCalls) {
                if (closeCall.getScope().isEmpty()) continue;
                String varName = closeCall.getScope().get().toString().trim();

                Node parent = tryStmt.getParentNode().orElse(null);
                if (!(parent instanceof BlockStmt parentBlock)) {
                    continue;
                }

                int tryIndex = parentBlock.getStatements().indexOf(tryStmt);
                if (tryIndex < 0) continue;

                VariableDeclarator targetDeclarator = null;
                Statement declStmt = null;
                for (int i = tryIndex - 1; i >= 0; i--) {
                    Statement stmt = parentBlock.getStatements().get(i);
                    if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                        for (VariableDeclarator vd : stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables()) {
                            if (vd.getNameAsString().equals(varName)) {
                                targetDeclarator = vd;
                                declStmt = stmt;
                                break;
                            }
                        }
                    }
                    if (targetDeclarator != null) break;
                }

                Expression initializer = null;
                if (targetDeclarator != null) {
                    if (targetDeclarator.getInitializer().isPresent() && !targetDeclarator.getInitializer().get().isNullLiteralExpr()) {
                        initializer = targetDeclarator.getInitializer().get();
                    } else {
                        // Look for assignment inside tryBlock
                        BlockStmt tryBlock = tryStmt.getTryBlock();
                        Statement assignStmtToRemove = null;
                        for (Statement stmt : tryBlock.getStatements()) {
                            if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isAssignExpr()) {
                                AssignExpr assign = stmt.asExpressionStmt().getExpression().asAssignExpr();
                                if (assign.getTarget().toString().trim().equals(varName)) {
                                    initializer = assign.getValue();
                                    assignStmtToRemove = stmt;
                                    break;
                                }
                            }
                        }
                        if (assignStmtToRemove != null) {
                            tryBlock.getStatements().remove(assignStmtToRemove);
                        }
                    }

                    if (initializer != null) {
                        VariableDeclarationExpr resourceExpr = new VariableDeclarationExpr(
                                new VariableDeclarator(targetDeclarator.getType().clone(), varName, initializer.clone())
                        );
                        tryStmt.getResources().add(resourceExpr);

                        // Remove outer declaration
                        VariableDeclarationExpr parentVarDecl = declStmt.asExpressionStmt().getExpression().asVariableDeclarationExpr();
                        if (parentVarDecl.getVariables().size() == 1) {
                            parentBlock.getStatements().remove(declStmt);
                        } else {
                            parentVarDecl.getVariables().remove(targetDeclarator);
                        }
                    }
                } else {
                    tryStmt.getResources().add(new NameExpr(varName));
                }

                // Clean up close statement in finallyBlock
                Node current = closeCall;
                Statement stmtInFinally = null;
                while (current != null && current != finallyBlock) {
                    if (current instanceof Statement s && finallyBlock.getStatements().contains(s)) {
                        stmtInFinally = s;
                        break;
                    }
                    current = current.getParentNode().orElse(null);
                }

                if (stmtInFinally != null) {
                    finallyBlock.getStatements().remove(stmtInFinally);
                }

                if (finallyBlock.getStatements().isEmpty()) {
                    tryStmt.removeFinallyBlock();
                }

                modified = true;
            }
        }
        return modified;
    }

    private boolean transformNestedLoopsToStream(CompilationUnit cu) {
        boolean modified = false;
        for (ForEachStmt outerLoop : cu.findAll(ForEachStmt.class)) {
            List<ForEachStmt> innerLoops = outerLoop.getBody().findAll(ForEachStmt.class);
            if (innerLoops.isEmpty()) {
                continue;
            }
            ForEachStmt innerLoop = innerLoops.get(0);

            String outerVarName = outerLoop.getVariable().getVariables().get(0).getNameAsString();
            Expression outerIterable = outerLoop.getIterable();

            String innerVarName = innerLoop.getVariable().getVariables().get(0).getNameAsString();
            Expression innerIterable = innerLoop.getIterable();

            Node parentNode = outerLoop.getParentNode().orElse(null);
            if (!(parentNode instanceof BlockStmt parentBlock)) {
                continue;
            }

            int loopIndex = parentBlock.getStatements().indexOf(outerLoop);
            if (loopIndex < 0) continue;

            List<MethodCallExpr> addCalls = innerLoop.getBody().findAll(MethodCallExpr.class).stream()
                    .filter(mc -> mc.getNameAsString().equals("add") && mc.getArguments().size() == 1)
                    .toList();

            if (!addCalls.isEmpty()) {
                MethodCallExpr addCall = addCalls.get(0);
                if (addCall.getScope().isPresent()) {
                    String targetListName = addCall.getScope().get().toString().trim();

                    VariableDeclarator targetVarDecl = null;
                    Statement targetDeclStmt = null;
                    for (int i = loopIndex - 1; i >= 0; i--) {
                        Statement stmt = parentBlock.getStatements().get(i);
                        if (stmt.isExpressionStmt() && stmt.asExpressionStmt().getExpression().isVariableDeclarationExpr()) {
                            for (VariableDeclarator vd : stmt.asExpressionStmt().getExpression().asVariableDeclarationExpr().getVariables()) {
                                if (vd.getNameAsString().equals(targetListName)) {
                                    targetVarDecl = vd;
                                    targetDeclStmt = stmt;
                                    break;
                                }
                            }
                        }
                        if (targetVarDecl != null) break;
                    }

                    List<IfStmt> ifStmts = innerLoop.getBody().findAll(IfStmt.class);
                    Expression condition = !ifStmts.isEmpty() ? ifStmts.get(0).getCondition() : null;

                    StringBuilder streamPipeline = new StringBuilder();
                    streamPipeline.append(outerIterable.toString()).append(".stream()");
                    streamPipeline.append(".flatMap(").append(outerVarName).append(" -> ").append(innerIterable.toString()).append(".stream())");
                    if (condition != null) {
                        streamPipeline.append(".filter(").append(innerVarName).append(" -> ").append(condition.toString()).append(")");
                    }
                    streamPipeline.append(".toList()");

                    if (targetVarDecl != null && targetDeclStmt != null) {
                        Expression newInit = StaticJavaParser.parseExpression(streamPipeline.toString());
                        targetVarDecl.setInitializer(newInit);
                        parentBlock.getStatements().remove(outerLoop);
                        modified = true;
                        break;
                    } else {
                        Statement newStmt = StaticJavaParser.parseStatement(targetListName + " = " + streamPipeline + ";");
                        parentBlock.getStatements().set(loopIndex, newStmt);
                        modified = true;
                        break;
                    }
                }
            } else {
                String actionBody = innerLoop.getBody().toString().trim();
                if (actionBody.startsWith("{") && actionBody.endsWith("}")) {
                    actionBody = actionBody.substring(1, actionBody.length() - 1).trim();
                }
                StringBuilder streamPipeline = new StringBuilder();
                streamPipeline.append(outerIterable.toString()).append(".stream()");
                streamPipeline.append(".flatMap(").append(outerVarName).append(" -> ").append(innerIterable.toString()).append(".stream())");
                streamPipeline.append(".forEach(").append(innerVarName).append(" -> { ").append(actionBody).append(" });");

                Statement newStmt = StaticJavaParser.parseStatement(streamPipeline.toString());
                parentBlock.getStatements().set(loopIndex, newStmt);
                modified = true;
                break;
            }
        }
        return modified;
    }

    private boolean annotateWithProjectionRecommendation(CompilationUnit cu) {
        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            cid.setComment(new com.github.javaparser.ast.comments.LineComment(" TODO APP-DB-005: replace SELECT * with dedicated projection DTO"));
            return true;
        }
        return false;
    }

    private boolean transformBatch5Builders(CompilationUnit cu) {
        boolean modified = false;

        // 1. Transform jobBuilderFactory.get("name") -> new JobBuilder("name", jobRepository)
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("get") && call.getScope().isPresent()) {
                String scope = call.getScope().get().toString().toLowerCase();
                if (scope.contains("jobbuilder") && !call.getArguments().isEmpty()) {
                    Expression arg = call.getArguments().get(0);
                    ObjectCreationExpr newJobBuilder = new ObjectCreationExpr(
                            null,
                            StaticJavaParser.parseClassOrInterfaceType("JobBuilder"),
                            new NodeList<>(arg.clone(), new NameExpr("jobRepository"))
                    );
                    call.replace(newJobBuilder);
                    modified = true;
                } else if (scope.contains("stepbuilder") && !call.getArguments().isEmpty()) {
                    Expression arg = call.getArguments().get(0);
                    ObjectCreationExpr newStepBuilder = new ObjectCreationExpr(
                            null,
                            StaticJavaParser.parseClassOrInterfaceType("StepBuilder"),
                            new NodeList<>(arg.clone(), new NameExpr("jobRepository"))
                    );
                    call.replace(newStepBuilder);
                    modified = true;
                }
            }
        }

        // 2. Transform Fields: JobBuilderFactory / StepBuilderFactory -> JobRepository
        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean hasJobRepoField = cid.getFields().stream()
                    .anyMatch(f -> f.getElementType().asString().equals("JobRepository"));

            List<FieldDeclaration> toRemove = new ArrayList<>();
            for (FieldDeclaration field : cid.getFields()) {
                String typeName = field.getElementType().asString();
                if (typeName.contains("JobBuilderFactory")) {
                    if (!hasJobRepoField) {
                        field.getVariable(0).setType("JobRepository");
                        field.getVariable(0).setName("jobRepository");
                        hasJobRepoField = true;
                        modified = true;
                    } else {
                        toRemove.add(field);
                    }
                } else if (typeName.contains("StepBuilderFactory")) {
                    if (!hasJobRepoField) {
                        field.getVariable(0).setType("JobRepository");
                        field.getVariable(0).setName("jobRepository");
                        hasJobRepoField = true;
                        modified = true;
                    } else {
                        toRemove.add(field);
                    }
                }
            }
            toRemove.forEach(FieldDeclaration::remove);

            // 3. Transform Constructors
            for (ConstructorDeclaration constructor : cid.getConstructors()) {
                boolean hasJobRepoParam = constructor.getParameters().stream()
                        .anyMatch(p -> p.getType().asString().equals("JobRepository"));

                List<Parameter> paramsToRemove = new ArrayList<>();
                for (Parameter param : constructor.getParameters()) {
                    String pType = param.getType().asString();
                    if (pType.contains("JobBuilderFactory")) {
                        if (!hasJobRepoParam) {
                            param.setType("JobRepository");
                            param.setName("jobRepository");
                            hasJobRepoParam = true;
                            modified = true;
                        } else {
                            paramsToRemove.add(param);
                        }
                    } else if (pType.contains("StepBuilderFactory")) {
                        if (!hasJobRepoParam) {
                            param.setType("JobRepository");
                            param.setName("jobRepository");
                            hasJobRepoParam = true;
                            modified = true;
                        } else {
                            paramsToRemove.add(param);
                        }
                    }
                }
                paramsToRemove.forEach(Parameter::remove);

                // Update constructor body assignments
                constructor.getBody().findAll(AssignExpr.class).forEach(assign -> {
                    String target = assign.getTarget().toString();
                    if (target.contains("stepBuilderFactory")) {
                        assign.getParentNode().ifPresent(p -> {
                            if (p instanceof Statement s) s.remove();
                        });
                    } else if (target.contains("jobBuilderFactory")) {
                        assign.setTarget(new NameExpr("this.jobRepository"));
                        assign.setValue(new NameExpr("jobRepository"));
                    }
                });
            }
        }

        // 4. Update imports
        if (modified) {
            cu.getImports().removeIf(i -> i.getNameAsString().contains("JobBuilderFactory") ||
                                          i.getNameAsString().contains("StepBuilderFactory"));
            cu.addImport("org.springframework.batch.core.job.builder.JobBuilder");
            cu.addImport("org.springframework.batch.core.step.builder.StepBuilder");
            cu.addImport("org.springframework.batch.core.repository.JobRepository");
        }

        return modified;
    }

    private boolean transformFileToPath(CompilationUnit cu) {
        boolean modified = false;

        // 1. Replace new File(...) with Path.of(...)
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            if (creation.getType().asString().equals("File") && !creation.getArguments().isEmpty()) {
                MethodCallExpr pathOf = new MethodCallExpr(
                        new NameExpr("Path"),
                        "of",
                        creation.getArguments()
                );
                creation.replace(pathOf);
                modified = true;
            }
        }

        // 2. Replace variable declarations: File var = Path.of(...) -> Path var = Path.of(...)
        for (VariableDeclarationExpr varDecl : cu.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator vd : varDecl.getVariables()) {
                if (vd.getType().asString().equals("File") &&
                        vd.getInitializer().map(init -> init.toString().contains("Path.of")).orElse(false)) {
                    vd.setType("Path");
                    modified = true;
                }
            }
        }

        // 3. Replace file.exists() -> Files.exists(file)
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("exists") && call.getArguments().isEmpty() && call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                MethodCallExpr filesExists = new MethodCallExpr(
                        new NameExpr("Files"),
                        "exists",
                        new NodeList<>(scope.clone())
                );
                call.replace(filesExists);
                modified = true;
            }
        }

        // 4. Update imports
        if (modified) {
            cu.addImport("java.nio.file.Path");
            cu.addImport("java.nio.file.Files");
        }

        return modified;
    }

    private boolean transformSwitchExpressions(CompilationUnit cu) {
        boolean modified = false;

        for (SwitchStmt switchStmt : cu.findAll(SwitchStmt.class)) {
            boolean allReturn = !switchStmt.getEntries().isEmpty() && switchStmt.getEntries().stream()
                    .allMatch(e -> e.getStatements().size() == 1 && e.getStatements().get(0).isReturnStmt());

            StringBuilder sb = new StringBuilder();
            if (allReturn) {
                sb.append("return switch (").append(switchStmt.getSelector().toString()).append(") {\n");
                for (SwitchEntry entry : switchStmt.getEntries()) {
                    String labels = entry.getLabels().isEmpty() ? "default" :
                            "case " + String.join(", ", entry.getLabels().stream().map(Expression::toString).toList());
                    Expression returnVal = entry.getStatements().get(0).asReturnStmt().getExpression().orElse(null);
                    sb.append("    ").append(labels).append(" -> ").append(returnVal != null ? returnVal.toString() : "").append(";\n");
                }
                sb.append("};");

                Statement newStmt = StaticJavaParser.parseStatement(sb.toString());
                switchStmt.replace(newStmt);
                modified = true;
            } else {
                sb.append("switch (").append(switchStmt.getSelector().toString()).append(") {\n");
                for (SwitchEntry entry : switchStmt.getEntries()) {
                    String labels = entry.getLabels().isEmpty() ? "default" :
                            "case " + String.join(", ", entry.getLabels().stream().map(Expression::toString).toList());
                    List<Statement> stmts = entry.getStatements().stream()
                            .filter(s -> !s.isBreakStmt())
                            .toList();
                    String body = stmts.size() == 1 ? stmts.get(0).toString().trim() :
                            "{ " + String.join(" ", stmts.stream().map(Statement::toString).toList()) + " }";
                    sb.append("    ").append(labels).append(" -> ").append(body).append("\n");
                }
                sb.append("}");

                Statement newStmt = StaticJavaParser.parseStatement(sb.toString());
                switchStmt.replace(newStmt);
                modified = true;
            }
        }

        return modified;
    }
}
