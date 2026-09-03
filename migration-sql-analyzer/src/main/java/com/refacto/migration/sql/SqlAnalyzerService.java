package com.refacto.migration.sql;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.FindingStatus;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.Finding;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Analyseur de requêtes SQL et d'interactions avec la base de données (Pack APP-DB-001 à APP-DB-007).
 */
public class SqlAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(SqlAnalyzerService.class);

    private static final Set<String> DIRECT_DB_TYPES = Set.of(
            "entitymanager", "jdbctemplate", "jdbcclient", "connection",
            "preparedstatement", "session", "sessionfactory", "sqlrowhandler"
    );

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
                            analyzeJavaFile(javaFile, moduleName, projectRoot, findings);
                        } catch (Exception e) {
                            log.debug("Erreur analyse SQL {}: {}", javaFile, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Erreur parcours {}: {}", srcMain, e.getMessage());
        }

        return findings;
    }

    private void analyzeJavaFile(Path javaFile, String moduleName, Path projectRoot, List<Finding> findings) throws Exception {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        String relativePath = projectRoot.relativize(javaFile).toString().replace('\\', '/');

        for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            checkDbAccessOutsideDao(cid, relativePath, moduleName, findings);
            checkDbCallsInsideLoops(cid, relativePath, moduleName, findings);
            checkEmbeddedSqlQueries(cid, relativePath, moduleName, findings);
            checkJpaMassProcessing(cid, relativePath, moduleName, findings);
            checkChunkBulkUpdates(cid, relativePath, moduleName, findings);
        }
    }

    /**
     * APP-DB-001: Centraliser les accès DB (Détecter les accès hors DAO).
     */
    private void checkDbAccessOutsideDao(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        String className = cid.getNameAsString();
        boolean isDaoOrRepo = className.endsWith("DAO") || className.endsWith("Dao") ||
                              className.endsWith("Repository") || className.endsWith("RepositoryImpl");

        if (isDaoOrRepo) return;

        // Check if class is a Service, Processor, Writer, Reader, Tasklet, etc.
        boolean isBusinessOrBatch = className.contains("Service") || className.contains("Processor") ||
                                    className.contains("Writer") || className.contains("Reader") ||
                                    className.contains("Tasklet") || className.contains("Job");

        for (FieldDeclaration field : cid.getFields()) {
            String typeName = field.getElementType().asString().toLowerCase();
            if (DIRECT_DB_TYPES.contains(typeName)) {
                findings.add(new Finding(
                        "APP-DB-001-" + UUID.randomUUID().toString().substring(0, 8),
                        null, null, moduleName,
                        "APP-DB-001", "1.0",
                        Category.DATABASE, Severity.HIGH, 98,
                        AutomationLevel.REVIEW_REQUIRED,
                        relativePath,
                        field.getBegin().map(p -> p.line).orElse(1),
                        field.getEnd().map(p -> p.line).orElse(1),
                        field.toString().trim(),
                        String.format("Accès direct à la base de données via '%s' en dehors de la couche DAO dans '%s'.",
                                field.getElementType().asString(), className),
                        "La roadmap APP impose de centraliser tous les accès aux données dans des DAO dédiés et de supprimer la logique d'accès aux données des classes métier ou batch.",
                        "Couplage fort et dispersion de la logique de persistance.",
                        "Déplacer les interactions SQL / JPA vers un DAO dédié et injecter le DAO dans " + className + ".",
                        4.0, "Medium", FindingStatus.OPEN,
                        field.toString().trim(),
                        className + " -> " + field.getElementType().asString() + " -> Database",
                        null
                ));
            }
        }
    }

    /**
     * APP-DB-004: Appels DB en boucle (N+1 queries / loop round-trips).
     */
    private void checkDbCallsInsideLoops(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        // 1. Classical loops (for, foreach, while)
        cid.findAll(ForStmt.class).forEach(loop -> inspectLoopBody(loop.getBody().toString(), loop.getBegin().map(p -> p.line).orElse(1), relativePath, moduleName, cid, findings));
        cid.findAll(ForEachStmt.class).forEach(loop -> inspectLoopBody(loop.getBody().toString(), loop.getBegin().map(p -> p.line).orElse(1), relativePath, moduleName, cid, findings));
        cid.findAll(WhileStmt.class).forEach(loop -> inspectLoopBody(loop.getBody().toString(), loop.getBegin().map(p -> p.line).orElse(1), relativePath, moduleName, cid, findings));

        // 2. Stream.forEach calls
        for (MethodCallExpr call : cid.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("forEach") && call.getScope().isPresent()) {
                String argStr = call.getArguments().toString();
                if (containsDbCallPattern(argStr)) {
                    findings.add(new Finding(
                            "APP-DB-004-" + UUID.randomUUID().toString().substring(0, 8),
                            null, null, moduleName,
                            "APP-DB-004", "1.0",
                            Category.DATABASE, Severity.HIGH, 95,
                            AutomationLevel.REVIEW_REQUIRED,
                            relativePath,
                            call.getBegin().map(p -> p.line).orElse(1),
                            call.getEnd().map(p -> p.line).orElse(1),
                            call.toString(),
                            "Appel persistance en boucle fonctionnelle (stream.forEach) provoquant des round-trips unitaires.",
                            "L'exécution d'un appel DB par élément entraîne un problème classique N+1 et sature le pool de connexions.",
                            "Latence critique et dégradation sous volumétrie batch (ex: 10 000 éléments = 10 000 round-trips).",
                            "Remplacer par un chargement ou une sauvegarde par lot (batch / bulk retrieval).",
                            6.0, "High", FindingStatus.OPEN,
                            call.toString(),
                            "stream().forEach(...) -> DB operation",
                            20000L
                    ));
                }
            }
        }
    }

    private void inspectLoopBody(String body, int line, String relativePath, String moduleName, ClassOrInterfaceDeclaration cid, List<Finding> findings) {
        if (containsDbCallPattern(body)) {
            findings.add(new Finding(
                    "APP-DB-004-" + UUID.randomUUID().toString().substring(0, 8),
                    null, null, moduleName,
                    "APP-DB-004", "1.0",
                    Category.DATABASE, Severity.HIGH, 96,
                    AutomationLevel.REVIEW_REQUIRED,
                    relativePath,
                    line, line + 10,
                    "for/while loop",
                    "Appels à la base de données ou méthode DAO détectés à l'intérieur d'une boucle.",
                    "L'appel unitaire dans une boucle entraîne des milliers d'allers-retours réseau inutiles. La roadmap APP demande de privilégier les opérations par lots.",
                    "Risque fort de congestion base de données et non-respect des fenêtres d'exécution batch.",
                    "Remplacer les accès unitaires par une opération par lot (findAllByIds / saveAll / SQL batch).",
                    6.0, "High", FindingStatus.OPEN,
                    body.substring(0, Math.min(body.length(), 200)) + "...",
                    "Loop -> Service/DAO -> Database",
                    40000L
            ));
        }
    }

    private boolean containsDbCallPattern(String text) {
        String lower = text.toLowerCase();
        return (lower.contains("dao.") || lower.contains("repository.") || lower.contains("entitymanager.") || lower.contains("jdbctemplate."))
                && (lower.contains("find") || lower.contains("save") || lower.contains("get") || lower.contains("update") || lower.contains("query"));
    }

    private void checkEmbeddedSqlQueries(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        // 1. Check variable declarations with string concatenations (e.g. "SELECT ... " + id + " FOR UPDATE")
        cid.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(var -> {
            if (var.getInitializer().isPresent()) {
                String exprStr = var.getInitializer().get().toString();
                String upper = exprStr.toUpperCase();
                if (upper.contains("SELECT") && upper.contains("FOR UPDATE")) {
                    findings.add(new Finding(
                            "APP-DB-003-" + UUID.randomUUID().toString().substring(0, 8),
                            null, null, moduleName,
                            "APP-DB-003", "1.0",
                            Category.DATABASE, Severity.HIGH, 97,
                            AutomationLevel.MANUAL_ONLY,
                            relativePath,
                            var.getBegin().map(p -> p.line).orElse(1),
                            var.getEnd().map(p -> p.line).orElse(1),
                            "SELECT FOR UPDATE",
                            "Requête 'SELECT FOR UPDATE' détectée.",
                            "La roadmap APP demande d'étudier un ordre de verrouillage déterministe, de limiter les données récupérées et la durée de transaction.",
                            "Risque d'interblocage",
                            "Ajouter un tri déterministe sur les clés primaires et évaluer SKIP LOCKED si fonctionnellement acceptable.",
                            4.0, "High", FindingStatus.OPEN,
                            exprStr, null, null
                    ));
                }
            }
        });

        // 2. Check string literals
        for (StringLiteralExpr str : cid.findAll(StringLiteralExpr.class)) {
            String val = str.getValue().trim();
            String upper = val.toUpperCase();
            if (upper.contains("FOR UPDATE") && findings.stream().noneMatch(f -> f.recipeId().equals("APP-DB-003") && f.filePath().equals(relativePath) && f.startLine() == str.getBegin().map(p -> p.line).orElse(1))) {
                findings.add(new Finding(
                        "APP-DB-003-" + UUID.randomUUID().toString().substring(0, 8),
                        null, null, moduleName,
                        "APP-DB-003", "1.0",
                        Category.DATABASE, Severity.HIGH, 97,
                        AutomationLevel.MANUAL_ONLY,
                        relativePath,
                        str.getBegin().map(p -> p.line).orElse(1),
                        str.getEnd().map(p -> p.line).orElse(1),
                        "SELECT FOR UPDATE",
                        "Requête 'SELECT FOR UPDATE' détectée.",
                        "Analyse de concurrence requise.",
                        "Risque de verrouillage",
                        "Valider l'ordre de tri et la durée de transaction.",
                        4.0, "High", FindingStatus.OPEN,
                        val, null, null
                ));
            }

            if (val.length() > 10 && upper.startsWith("SELECT")) {
                try {
                    Statement stmt = CCJSqlParserUtil.parse(val);
                    if (stmt instanceof PlainSelect select) {
                        analyzeSelectStatement(select, str, relativePath, moduleName, findings);
                    }
                } catch (Exception ignored) {
                    analyzeSqlRegexFallback(val, str, relativePath, moduleName, findings);
                }
            }
        }
    }

    private void analyzeSelectStatement(PlainSelect select, StringLiteralExpr str, String relativePath, String moduleName, List<Finding> findings) {
        String sql = select.toString();
        int line = str.getBegin().map(p -> p.line).orElse(1);

        // APP-DB-003: SELECT FOR UPDATE
        boolean isForUpdate = sql.toUpperCase().contains("FOR UPDATE");
        if (isForUpdate) {
            boolean hasOrderBy = select.getOrderByElements() != null && !select.getOrderByElements().isEmpty();
            String desc = "Requête 'SELECT FOR UPDATE' détectée.";
            if (!hasOrderBy) {
                desc += " Absence de clause ORDER BY déterministe (risque élevé de deadlocks concurrents).";
            }

            findings.add(new Finding(
                    "APP-DB-003-" + UUID.randomUUID().toString().substring(0, 8),
                    null, null, moduleName,
                    "APP-DB-003", "1.0",
                    Category.DATABASE, Severity.HIGH, 97,
                    AutomationLevel.MANUAL_ONLY,
                    relativePath,
                    line, line,
                    "SELECT FOR UPDATE",
                    desc,
                    "La roadmap APP demande d'étudier un ordre de verrouillage déterministe, de limiter les données récupérées et la durée de transaction. L'ajout de SKIP LOCKED n'est acceptable que sur validation fonctionnelle.",
                    "Risque de verrouillage bloquant ou interblocage (deadlock) lors d'exécutions parallèles.",
                    "Ajouter un tri déterministe sur les clés primaires, limiter le lot (LIMIT/ROWNUM) et évaluer SKIP LOCKED si fonctionnellement acceptable.",
                    4.0, "High", FindingStatus.OPEN,
                    sql, null, null
            ));
        }

        // APP-DB-005: SELECT *
        if (select.getSelectItems() != null) {
            boolean hasWildcard = select.getSelectItems().stream()
                    .anyMatch(item -> item.toString().equals("*") || item.toString().endsWith(".*"));
            if (hasWildcard) {
                findings.add(new Finding(
                        "APP-DB-005-" + UUID.randomUUID().toString().substring(0, 8),
                        null, null, moduleName,
                        "APP-DB-005", "1.0",
                        Category.DATABASE, Severity.MEDIUM, 94,
                        AutomationLevel.REVIEW_REQUIRED,
                        relativePath,
                        line, line,
                        "SELECT *",
                        "Usage de 'SELECT *' récupérant l'intégralité des colonnes de la table.",
                        "Transférer des colonnes non utilisées consomme de la bande passante et de la mémoire heap inutilement.",
                        "Surcharge I/O et désactivation possible des index couvrants (covering indexes).",
                        "Restreindre la projection aux seules colonnes strictement nécessaires au traitement métier.",
                        1.5, "Low", FindingStatus.OPEN,
                        sql, null, null
                ));
            }
        }

        // APP-DB-007: SQL Quality (Functions on filtered columns / COUNT vs EXISTS)
        if (sql.toUpperCase().contains("UPPER(") || sql.toUpperCase().contains("TRIM(") || sql.toUpperCase().contains("SUBSTR(")) {
            findings.add(new Finding(
                    "APP-DB-007-" + UUID.randomUUID().toString().substring(0, 8),
                    null, null, moduleName,
                    "APP-DB-007", "1.0",
                    Category.DATABASE, Severity.MEDIUM, 90,
                    AutomationLevel.REVIEW_REQUIRED,
                    relativePath,
                    line, line,
                    "Function on column",
                    "Application d'une fonction (UPPER, TRIM, SUBSTR) sur une colonne filtrée empêchant l'utilisation d'index standards.",
                    "Nécessite un index fonctionnel ou une harmonisation des données à l'insertion (Database plan required).",
                    "Table scan complet possible sous forte volumétrie.",
                    "Valider le plan d'exécution DB et créer un index fonctionnel ou adapter la requête.",
                    2.0, "Medium", FindingStatus.OPEN,
                    sql, null, null
            ));
        }

        if (sql.toUpperCase().contains("SELECT COUNT(*) FROM") && sql.toUpperCase().contains("WHERE")) {
            findings.add(new Finding(
                    "APP-DB-007-" + UUID.randomUUID().toString().substring(0, 8),
                    null, null, moduleName,
                    "APP-DB-007", "1.0",
                    Category.DATABASE, Severity.LOW, 88,
                    AutomationLevel.AUTO_WITH_TESTS,
                    relativePath,
                    line, line,
                    "COUNT(*) vs EXISTS",
                    "Usage de COUNT(*) pour tester la simple présence de données.",
                    "COUNT(*) parcourt tous les enregistrements correspondants, alors que EXISTS s'arrête dès le premier résultat.",
                    "Perte de performance si plusieurs lignes satisfont la condition.",
                    "Remplacer par une sous-requête EXISTS ou SELECT 1 ... LIMIT 1.",
                    1.0, "Low", FindingStatus.OPEN,
                    sql, null, null
            ));
        }
    }

    private void analyzeSqlRegexFallback(String sql, StringLiteralExpr str, String relativePath, String moduleName, List<Finding> findings) {
        String upper = sql.toUpperCase();
        int line = str.getBegin().map(p -> p.line).orElse(1);

        if (upper.contains("FOR UPDATE")) {
            findings.add(new Finding(
                    "APP-DB-003-" + UUID.randomUUID().toString().substring(0, 8),
                    null, null, moduleName,
                    "APP-DB-003", "1.0",
                    Category.DATABASE, Severity.HIGH, 92,
                    AutomationLevel.MANUAL_ONLY,
                    relativePath,
                    line, line,
                    "SELECT FOR UPDATE",
                    "Requête de verrouillage 'FOR UPDATE' détectée.",
                    "Analyse de concurrence et de déterminisme nécessaire conformément à la roadmap APP.",
                    "Risque d'interblocage",
                    "Valider l'ordre de tri et la durée de transaction.",
                    4.0, "High", FindingStatus.OPEN,
                    sql, null, null
            ));
        }

        if (upper.contains("SELECT *") || upper.contains("SELECT  *")) {
            findings.add(new Finding(
                    "APP-DB-005-" + UUID.randomUUID().toString().substring(0, 8),
                    null, null, moduleName,
                    "APP-DB-005", "1.0",
                    Category.DATABASE, Severity.MEDIUM, 90,
                    AutomationLevel.REVIEW_REQUIRED,
                    relativePath,
                    line, line,
                    "SELECT *",
                    "Usage de 'SELECT *' sans projection ciblée.",
                    "Surcharge réseau et mémoire.",
                    "Latence accrue",
                    "Spécifier les colonnes nécessaires.",
                    1.5, "Low", FindingStatus.OPEN,
                    sql, null, null
            ));
        }
    }

    /**
     * APP-DB-002: Traitements de masse JPA vs Traitements SQL directs.
     */
    private void checkJpaMassProcessing(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        for (MethodDeclaration method : cid.getMethods()) {
            String mStr = method.toString();
            boolean loadsEntities = mStr.contains("findAll") || mStr.contains("findByStatus") || mStr.contains("getResultList");
            boolean loopsAndSaves = (mStr.contains("for (") || mStr.contains(".forEach(")) && (mStr.contains("save(") || mStr.contains("saveAll("));

            if (loadsEntities && loopsAndSaves) {
                findings.add(new Finding(
                        "APP-DB-002-" + UUID.randomUUID().toString().substring(0, 8),
                        null, null, moduleName,
                        "APP-DB-002", "1.0",
                        Category.DATABASE, Severity.HIGH, 91,
                        AutomationLevel.REVIEW_REQUIRED,
                        relativePath,
                        method.getBegin().map(p -> p.line).orElse(1),
                        method.getEnd().map(p -> p.line).orElse(1),
                        cid.getNameAsString() + "." + method.getNameAsString(),
                        "Traitement de masse via chargement d'objets JPA et boucle de mise à jour.",
                        "Charger des milliers d'entités en session JPA pour appliquer une modification uniforme surcharge la mémoire heap et multiplie les dirty checks Hibernate. Le document APP demande de privilégier les traitements SQL directs (UPDATE/INSERT/DELETE).",
                        "Risque OutOfMemoryError et temps d'exécution excessif sous forte volumétrie.",
                        "Évaluer le remplacement du chargement/boucle par une requête SQL directe 'UPDATE ... SET ... WHERE ...'.",
                        8.0, "High", FindingStatus.OPEN,
                        method.toString().substring(0, Math.min(method.toString().length(), 200)) + "...",
                        "SELECT entities -> load JPA objects -> loop -> update",
                        null
                ));
            }
        }
    }

    /**
     * APP-DB-006: Bulk updates dans les chunks Spring Batch.
     */
    private void checkChunkBulkUpdates(ClassOrInterfaceDeclaration cid, String relativePath, String moduleName, List<Finding> findings) {
        if (cid.getNameAsString().endsWith("Writer")) {
            for (MethodDeclaration m : cid.getMethods()) {
                String mStr = m.toString();
                if (m.getNameAsString().equals("write") && mStr.contains("for (") &&
                        (mStr.contains(".save(") || mStr.contains(".merge(") || mStr.contains(".persist("))) {
                    findings.add(new Finding(
                            "APP-DB-006-" + UUID.randomUUID().toString().substring(0, 8),
                            null, null, moduleName,
                            "APP-DB-006", "1.0",
                            Category.DATABASE, Severity.MEDIUM, 88,
                            AutomationLevel.MANUAL_ONLY,
                            relativePath,
                            m.getBegin().map(p -> p.line).orElse(1),
                            m.getEnd().map(p -> p.line).orElse(1),
                            cid.getNameAsString() + ".write",
                            "Écriture chunk par chunk avec sauvegarde individuelle d'entités.",
                            "La roadmap APP souligne que la suppression ou modification du fonctionnement par chunk peut provoquer des régressions et nécessite une analyse de faisabilité au cas par cas.",
                            "Risque de régression sur la reprise sur erreur (restartability) du step batch.",
                            "Étudier la faisabilité d'un JDBC Batch Update ou d'une opération SQL globale.",
                            6.0, "High", FindingStatus.OPEN,
                            m.toString().substring(0, Math.min(m.toString().length(), 180)) + "...",
                            "chunk -> items -> update item -> save",
                            null
                    ));
                }
            }
        }
    }
}
