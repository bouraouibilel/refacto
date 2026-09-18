package com.refacto.migration.transformation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.refacto.migration.core.model.CommonModuleSlice;
import com.refacto.migration.core.model.ModuleReorganizationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Service de découpage, duplication et spécialisation des modules communs (ex: legacy-common).
 * Pour chaque groupe de sous-projet (Bounded Context), ce service :
 * 1. Identifie les classes du module commun effectivement utilisées par les modules du sous-projet.
 * 2. Calcule la fermeture transitive des dépendances internes au module commun.
 * 3. Élimine tout le code non référencé (élagage / Dead Code Elimination).
 */
public class CommonModuleSpecializerService {

    private static final Logger log = LoggerFactory.getLogger(CommonModuleSpecializerService.class);

    public record CommonClassInfo(
            String simpleName,
            String qualifiedName,
            String relativeSourcePath,
            Set<String> internalDependencies
    ) {}

    /**
     * Détermine si un nom de module correspond à un module commun transverse.
     */
    public boolean isCommonModule(Path rootPath, String moduleDir) {
        if (moduleDir == null || moduleDir.isBlank()) return false;
        String lower = moduleDir.toLowerCase();
        if (lower.contains("common") || lower.contains("shared") || lower.contains("util")) {
            return true;
        }
        Path pomPath = rootPath.resolve(moduleDir).resolve("pom.xml");
        if (Files.exists(pomPath)) {
            try {
                String pom = Files.readString(pomPath, StandardCharsets.UTF_8);
                return pom.contains("<artifactId>") &&
                        (pom.contains("common") || pom.contains("shared") || pom.contains("util"));
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Analyse un module commun et extrait la cartographie de toutes ses classes Java.
     */
    public Map<String, CommonClassInfo> scanCommonModuleClasses(Path rootPath, String commonModuleDir) {
        Map<String, CommonClassInfo> classMap = new LinkedHashMap<>();
        Path moduleRoot = rootPath.resolve(commonModuleDir);
        Path srcMainJava = moduleRoot.resolve("src").resolve("main").resolve("java");

        if (!Files.exists(srcMainJava) || !Files.isDirectory(srcMainJava)) {
            return classMap;
        }

        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(srcMainJava)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                  .forEach(javaFiles::add);
        } catch (IOException e) {
            log.warn("Erreur lors du scan du module commun {} : {}", commonModuleDir, e.getMessage());
            return classMap;
        }

        // 1ère passe : identifier les noms simples et qualifiés
        for (Path file : javaFiles) {
            String relToModule = moduleRoot.relativize(file).toString().replace('\\', '/');
            String simpleName = file.getFileName().toString().replace(".java", "");
            String qualifiedName = extractQualifiedName(srcMainJava, file, simpleName);
            classMap.put(simpleName, new CommonClassInfo(simpleName, qualifiedName, relToModule, new HashSet<>()));
        }

        // 2ème passe : analyser les dépendances internes entre classes du module commun
        for (Path file : javaFiles) {
            String simpleName = file.getFileName().toString().replace(".java", "");
            CommonClassInfo info = classMap.get(simpleName);
            if (info == null) continue;

            Set<String> internalDeps = detectInternalDependencies(file, classMap.keySet(), simpleName);
            info.internalDependencies().addAll(internalDeps);
        }

        return classMap;
    }

    /**
     * Calcule la tranche spécialisée du module commun pour un groupe de sous-projet.
     */
    public CommonModuleSlice specializeForSubProject(
            Path rootPath,
            String commonModuleName,
            String targetSubProjectDir,
            List<ModuleReorganizationItem> childModules,
            Map<String, CommonClassInfo> commonClassMap
    ) {
        if (commonClassMap == null || commonClassMap.isEmpty()) {
            commonClassMap = scanCommonModuleClasses(rootPath, commonModuleName);
        }

        if (commonClassMap.isEmpty()) {
            return new CommonModuleSlice(commonModuleName, commonModuleName,
                    targetSubProjectDir + "/" + commonModuleName, List.of(), List.of(), false);
        }

        // 1. Détecter les références directes dans les modules enfants du sous-projet
        Set<String> directlyReferenced = new HashSet<>();
        for (ModuleReorganizationItem child : childModules) {
            if (!child.included()) continue;
            Path childModuleDir = rootPath.resolve(child.originalRelativePath());
            scanReferencesInModule(childModuleDir, commonClassMap, directlyReferenced);
        }

        // 2. Calculer la fermeture transitive interne au module commun
        Set<String> allRetainedNames = new HashSet<>(directlyReferenced);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String retained : new ArrayList<>(allRetainedNames)) {
                CommonClassInfo info = commonClassMap.get(retained);
                if (info != null) {
                    for (String internalDep : info.internalDependencies()) {
                        if (allRetainedNames.add(internalDep)) {
                            changed = true;
                        }
                    }
                }
            }
        }

        // 3. Partitionner en classes conservées et classes élaguées
        List<String> retainedPaths = new ArrayList<>();
        List<String> prunedPaths = new ArrayList<>();

        for (CommonClassInfo info : commonClassMap.values()) {
            if (allRetainedNames.contains(info.simpleName())) {
                retainedPaths.add(info.relativeSourcePath());
            } else {
                prunedPaths.add(info.relativeSourcePath());
            }
        }

        String targetSliceName = commonModuleName;
        String targetRelativePath = targetSubProjectDir + "/" + targetSliceName;
        boolean included = !retainedPaths.isEmpty();

        return new CommonModuleSlice(
                commonModuleName,
                targetSliceName,
                targetRelativePath,
                retainedPaths,
                prunedPaths,
                included
        );
    }

    /**
     * Génère le pom.xml épuré pour le module commun localisé dans le sous-projet.
     */
    public String computeRevisedCommonModulePom(
            String originalPomContent,
            String targetSubProjectDir,
            CommonModuleSlice slice
    ) {
        return computeRevisedCommonModulePom(originalPomContent, targetSubProjectDir, targetSubProjectDir, slice);
    }

    public String computeRevisedCommonModulePom(
            String originalPomContent,
            String targetSubProjectDir,
            String parentArtifactId,
            CommonModuleSlice slice
    ) {
        if (originalPomContent == null || originalPomContent.isBlank()) {
            return String.format(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                    "    <modelVersion>4.0.0</modelVersion>\n" +
                    "    <parent>\n" +
                    "        <groupId>com.sample</groupId>\n" +
                    "        <artifactId>%s</artifactId>\n" +
                    "        <version>1.0.0</version>\n" +
                    "        <relativePath>../pom.xml</relativePath>\n" +
                    "    </parent>\n" +
                    "    <artifactId>%s</artifactId>\n" +
                    "    <packaging>jar</packaging>\n" +
                    "</project>\n",
                    parentArtifactId != null && !parentArtifactId.isBlank() ? parentArtifactId : targetSubProjectDir,
                    slice.targetModuleName()
            );
        }

        String revised = originalPomContent;

        // Mettre à jour relativePath et artifactId du parent si présent
        if (revised.contains("<parent>")) {
            if (parentArtifactId != null && !parentArtifactId.isBlank()) {
                Matcher parentMatcher = Pattern.compile("(?s)<parent>(.*?)</parent>").matcher(revised);
                if (parentMatcher.find()) {
                    String parentBlock = parentMatcher.group(1);
                    String updatedParentBlock = parentBlock.replaceFirst(
                            "<artifactId>\\s*([^<\\s]+)\\s*</artifactId>",
                            "<artifactId>" + Matcher.quoteReplacement(parentArtifactId) + "</artifactId>"
                    );
                    revised = revised.substring(0, parentMatcher.start(1)) + updatedParentBlock + revised.substring(parentMatcher.end(1));
                }
            }
            revised = revised.replaceAll("<relativePath>.*?</relativePath>", "<relativePath>../pom.xml</relativePath>");
            if (!revised.contains("<relativePath>")) {
                revised = revised.replace("</parent>", "    <relativePath>../pom.xml</relativePath>\n    </parent>");
            }
        }

        return revised;
    }

    /**
     * Applique physiquement la duplication et l'élagage des classes non utilisées.
     */
    public void applySlicePhysically(
            Path rootPath,
            String targetSubProjectDir,
            CommonModuleSlice slice,
            String originalRootPom
    ) throws IOException {
        applySlicePhysically(rootPath, targetSubProjectDir, targetSubProjectDir, slice, originalRootPom);
    }

    public void applySlicePhysically(
            Path rootPath,
            String targetSubProjectDir,
            String parentArtifactId,
            CommonModuleSlice slice,
            String originalRootPom
    ) throws IOException {
        Path origCommonDir = rootPath.resolve(slice.originalModuleName());
        Path targetSliceDir = rootPath.resolve(slice.targetRelativePath());

        if (!Files.exists(origCommonDir)) {
            log.warn("Module commun source introuvable : {}", origCommonDir);
            return;
        }

        // 1. Copier l'arborescence complète du module commun vers le sous-projet cible
        copyDirectoryExcludingBuild(origCommonDir, targetSliceDir);

        // 2. Supprimer physiquement chaque classe élaguée
        for (String prunedRelPath : slice.prunedClasses()) {
            Path fileToDelete = targetSliceDir.resolve(prunedRelPath);
            if (Files.exists(fileToDelete)) {
                Files.delete(fileToDelete);
                log.info("Élagage classe non référencée : {}", fileToDelete);
                cleanEmptyParentDirectories(fileToDelete.getParent(), targetSliceDir);
            }
        }

        // 3. Écrire le pom.xml du module commun localisé
        Path origPom = origCommonDir.resolve("pom.xml");
        String origPomContent = Files.exists(origPom) ? Files.readString(origPom, StandardCharsets.UTF_8) : "";
        String revisedPom = computeRevisedCommonModulePom(origPomContent, targetSubProjectDir, parentArtifactId, slice);
        Files.writeString(targetSliceDir.resolve("pom.xml"), revisedPom, StandardCharsets.UTF_8);
    }

    private void scanReferencesInModule(
            Path moduleDir,
            Map<String, CommonClassInfo> commonClassMap,
            Set<String> foundReferences
    ) {
        Path src = moduleDir.resolve("src");
        if (!Files.exists(src) || !Files.isDirectory(src)) return;

        try (Stream<Path> stream = Files.walk(src)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                  .forEach(file -> {
                      try {
                          String content = Files.readString(file, StandardCharsets.UTF_8);
                          for (Map.Entry<String, CommonClassInfo> entry : commonClassMap.entrySet()) {
                              String simpleName = entry.getKey();
                              CommonClassInfo info = entry.getValue();

                              // Vérifier import qualifié ou usage du nom simple
                              if (content.contains("import " + info.qualifiedName()) ||
                                  content.contains("import " + extractPackage(info.qualifiedName()) + ".*") ||
                                  content.matches("(?s).*\\b" + Pattern.quote(simpleName) + "\\b.*")) {
                                  foundReferences.add(simpleName);
                              }
                          }
                      } catch (Exception ignored) {}
                  });
        } catch (IOException ignored) {}
    }

    private Set<String> detectInternalDependencies(Path javaFile, Set<String> allCommonClasses, String currentClass) {
        Set<String> deps = new HashSet<>();
        try {
            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
            for (String otherClass : allCommonClasses) {
                if (otherClass.equals(currentClass)) continue;
                if (content.matches("(?s).*\\b" + Pattern.quote(otherClass) + "\\b.*")) {
                    deps.add(otherClass);
                }
            }
        } catch (Exception ignored) {}
        return deps;
    }

    private String extractQualifiedName(Path srcMainJava, Path file, String simpleName) {
        Path rel = srcMainJava.relativize(file);
        String p = rel.toString().replace('\\', '/').replace(".java", "");
        return p.replace('/', '.');
    }

    private String extractPackage(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf('.');
        return idx > 0 ? qualifiedName.substring(0, idx) : "";
    }

    private void copyDirectoryExcludingBuild(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path rel = source.relativize(src);
                String relStr = rel.toString().replace('\\', '/');

                // Exclure dossiers de build ou git
                if (relStr.startsWith("target") || relStr.startsWith(".git")) {
                    continue;
                }

                Path dest = target.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void cleanEmptyParentDirectories(Path dir, Path stopAt) {
        try {
            Path current = dir;
            while (current != null && !current.equals(stopAt) && Files.isDirectory(current)) {
                try (var s = Files.list(current)) {
                    if (s.findAny().isEmpty()) {
                        Files.delete(current);
                        current = current.getParent();
                    } else {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
