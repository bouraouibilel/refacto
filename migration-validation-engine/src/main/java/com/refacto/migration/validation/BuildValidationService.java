package com.refacto.migration.validation;

import com.refacto.migration.core.enums.ValidationStatus;
import com.refacto.migration.core.model.BuildExecution;
import com.refacto.migration.core.model.TestExecution;
import com.refacto.migration.core.model.ValidationResult;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Moteur de validation technique (Section 42) : exécute la compilation Maven et les tests.
 */
public class BuildValidationService {

    private static final Logger log = LoggerFactory.getLogger(BuildValidationService.class);

    public ValidationResult validateProject(Path projectRoot, boolean runTests) {
        long startTime = System.currentTimeMillis();
        String command = runTests ? "mvn test-compile" : "mvn compile";

        BuildExecution buildExec = executeMavenCommand(projectRoot, runTests ? new String[]{"mvn.cmd", "test-compile"} : new String[]{"mvn.cmd", "compile"});
        if (buildExec.exitCode() != 0) {
            // Fallback for non-windows or mvn wrapper
            buildExec = executeMavenCommand(projectRoot, new String[]{"mvn", runTests ? "test-compile" : "compile"});
        }

        ValidationStatus buildStatus = buildExec.exitCode() == 0 ? ValidationStatus.PASS : ValidationStatus.FAIL;

        TestExecution testExec = null;
        ValidationStatus testStatus = ValidationStatus.NOT_RUN;

        if (buildStatus == ValidationStatus.PASS && runTests) {
            BuildExecution testRun = executeMavenCommand(projectRoot, new String[]{"mvn.cmd", "test"});
            if (testRun.exitCode() != 0) {
                testRun = executeMavenCommand(projectRoot, new String[]{"mvn", "test"});
            }

            testStatus = testRun.exitCode() == 0 ? ValidationStatus.PASS : ValidationStatus.FAIL;
            testExec = new TestExecution(
                    1,
                    testStatus == ValidationStatus.PASS ? 1 : 0,
                    testStatus == ValidationStatus.FAIL ? 1 : 0,
                    0,
                    testRun.durationMs(),
                    testStatus,
                    testStatus == ValidationStatus.FAIL ? "Certains tests ont échoué lors de la validation." : null
            );
        }

        ValidationStatus overall = (buildStatus == ValidationStatus.PASS && (testStatus == ValidationStatus.PASS || testStatus == ValidationStatus.NOT_RUN))
                ? ValidationStatus.PASS : ValidationStatus.FAIL;

        String summary = overall == ValidationStatus.PASS ?
                "Validation technique réussie (Compilation PASS, Tests " + testStatus + ")." :
                "Échec de la validation technique (Build " + buildStatus + ").";

        return new ValidationResult(
                buildStatus,
                testStatus,
                ValidationStatus.NOT_RUN,
                ValidationStatus.NOT_RUN,
                ValidationStatus.NOT_RUN,
                overall,
                summary,
                buildExec,
                testExec,
                null
        );
    }

    private BuildExecution executeMavenCommand(Path workingDir, String[] cmd) {
        long start = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);

            long duration = System.currentTimeMillis() - start;
            if (!finished) {
                process.destroyForcibly();
                return new BuildExecution(String.join(" ", cmd), -1, duration, ValidationStatus.FAIL, "Timeout de 60s dépassé.");
            }

            String logs = IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            ValidationStatus status = exitCode == 0 ? ValidationStatus.PASS : ValidationStatus.FAIL;

            return new BuildExecution(String.join(" ", cmd), exitCode, duration, status, logs);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new BuildExecution(String.join(" ", cmd), -1, duration, ValidationStatus.FAIL, "Erreur système: " + e.getMessage());
        }
    }
}
