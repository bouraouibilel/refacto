package com.refacto.migration.transformation;

import com.refacto.migration.core.model.ModuleDescriptor;
import com.refacto.migration.core.model.ModuleReorganizationItem;
import com.refacto.migration.core.model.ModuleReorganizationPlan;
import com.refacto.migration.core.model.PackagingSubProjectGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service de réorganisation modulaire :
 * Transforme chaque groupe de modules défini par un module de packaging en une structure
 * de sous-projet (nommée d'après le packaging sans préfixe packaging-) et y déplace
 * les modules enfants référencés.
 */
public class ModuleReorganizationService {

    private static final Logger log = LoggerFactory.getLogger(ModuleReorganizationService.class);

    private final DiffGeneratorService diffGenerator = new DiffGeneratorService();

    /**
     * Analyse le projet pour détecter les packagings et leurs modules enfants associés.
     */
    public ModuleReorganizationPlan analyzeReorganization(Path rootPath, List<ModuleDescriptor> modules) {
        if (rootPath == null || !Files.exists(rootPath)) {
            return ModuleReorganizationPlan.empty();
        }

        Path rootPomPath = rootPath.resolve("pom.xml");
        if (!Files.exists(rootPomPath)) {
            return ModuleReorganizationPlan.empty();
        }

        List<String> rootDeclaredModules = readDeclaredModulesFromPom(rootPomPath);
        if (rootDeclaredModules.isEmpty() && modules != null) {
            rootDeclaredModules = modules.stream().map(ModuleDescriptor::relativePath).toList();
        }

        List<PackagingSubProjectGroup> groups = new ArrayList<>();
        Set<String> assignedChildDirs = new HashSet<>();
        Set<String> packagingDirs = new HashSet<>();

        // 1. Identifier les modules de packaging
        for (String modDir : rootDeclaredModules) {
            if (isPackagingModule(rootPath, modDir)) {
                packagingDirs.add(modDir);
            }
        }

        // 2. Pour chaque packaging, trouver les dépendances internes vers d'autres modules du projet
        for (String pkgDir : packagingDirs) {
            Path pkgPomPath = rootPath.resolve(pkgDir).resolve("pom.xml");
            String artifactId = extractArtifactId(pkgPomPath, pkgDir);
            List<String> referencedArtifacts = extractReferencedArtifacts(pkgPomPath);

            String targetSubDir = computeTargetSubProjectDir(pkgDir, rootDeclaredModules);

            List<ModuleReorganizationItem> childItems = new ArrayList<>();
            for (String otherModDir : rootDeclaredModules) {
                if (otherModDir.equals(pkgDir)) continue;

                Path otherPom = rootPath.resolve(otherModDir).resolve("pom.xml");
                String otherArtifactId = extractArtifactId(otherPom, otherModDir);

                // Vérifier si le packaging référence ce module ou s'il partage le même domaine fonctionnel
                if (referencedArtifacts.contains(otherArtifactId) || matchesPackagingDomain(pkgDir, otherModDir)) {
                    String childTargetRelPath = targetSubDir + "/" + otherModDir;
                    childItems.add(new ModuleReorganizationItem(otherArtifactId, otherModDir, childTargetRelPath, true));
                    assignedChildDirs.add(otherModDir);
                }
            }

            groups.add(new PackagingSubProjectGroup(
                    artifactId,
                    pkgDir,
                    targetSubDir,
                    childItems,
                    true,
                    "PROPOSED"
            ));
        }

        // 3. Modules restants non rattachés
        List<String> unassigned = new ArrayList<>();
        for (String modDir : rootDeclaredModules) {
            if (!packagingDirs.contains(modDir) && !assignedChildDirs.contains(modDir)) {
                unassigned.add(modDir);
            }
        }

        if (groups.isEmpty()) {
            return ModuleReorganizationPlan.empty();
        }

        ModuleReorganizationPlan initialPlan = new ModuleReorganizationPlan(
                "pom.xml",
                groups,
                unassigned,
                "",
                Map.of(),
                List.of(),
                false,
                "Proposition de restructuration en sous-projets générée avec succès."
        );

        return previewCustomizedPlan(rootPath, initialPlan);
    }

    /**
     * Recalcule les diffs et les commandes git en fonction des modifications apportées par l'utilisateur.
     */
    public ModuleReorganizationPlan previewCustomizedPlan(Path rootPath, ModuleReorganizationPlan plan) {
        Path rootPomPath = rootPath.resolve("pom.xml");
        String originalRootPomContent = readFileContent(rootPomPath);

        // 1. Calculer le nouveau contenu du POM racine
        String revisedRootPomContent = computeRevisedRootPom(originalRootPomContent, plan);
        String rootDiff = diffGenerator.generateUnifiedDiff("pom.xml", originalRootPomContent, revisedRootPomContent);

        // 2. Calculer les diffs pour les POMs des sous-projets
        Map<String, String> subProjectDiffs = new LinkedHashMap<>();
        List<String> gitCommands = new ArrayList<>();

        for (PackagingSubProjectGroup group : plan.groups()) {
            if (!group.included()) continue;

            Path originalPkgPom = rootPath.resolve(group.originalPackagingDir()).resolve("pom.xml");
            String originalPkgPomContent = readFileContent(originalPkgPom);
            String revisedPkgPomContent = computeRevisedSubProjectPom(originalPkgPomContent, group);

            String diffKey = group.targetSubProjectDir() + "/pom.xml";
            subProjectDiffs.put(diffKey, diffGenerator.generateUnifiedDiff(diffKey, originalPkgPomContent, revisedPkgPomContent));

            // Commande git pour déplacer le dossier packaging vers le sous-projet
            gitCommands.add(String.format("git mv \"%s\" \"%s\"", group.originalPackagingDir(), group.targetSubProjectDir()));

            // Commandes git pour déplacer chaque module enfant dans le sous-projet
            for (ModuleReorganizationItem child : group.childModules()) {
                if (child.included()) {
                    gitCommands.add(String.format("git mv \"%s\" \"%s/%s\"",
                            child.originalRelativePath(), group.targetSubProjectDir(), child.originalRelativePath()));
                }
            }
        }

        return new ModuleReorganizationPlan(
                plan.parentPomPath(),
                plan.groups(),
                plan.unassignedModules(),
                rootDiff,
                subProjectDiffs,
                gitCommands,
                false,
                plan.summary()
        );
    }

    /**
     * Applique physiquement la réorganisation sur le disque :
     * Déplace les dossiers et met à jour les fichiers pom.xml.
     */
    public ModuleReorganizationPlan applyReorganization(Path rootPath, ModuleReorganizationPlan plan) throws IOException {
        ModuleReorganizationPlan computedPlan = previewCustomizedPlan(rootPath, plan);

        boolean isGit = Files.exists(rootPath.resolve(".git"));

        for (PackagingSubProjectGroup group : computedPlan.groups()) {
            if (!group.included()) continue;

            Path origPkgDir = rootPath.resolve(group.originalPackagingDir());
            Path targetSubDir = rootPath.resolve(group.targetSubProjectDir());

            // 1. Déplacer le répertoire du packaging vers le sous-projet
            if (Files.exists(origPkgDir)) {
                if (!origPkgDir.equals(targetSubDir)) {
                    safeMoveDirectory(rootPath, origPkgDir, targetSubDir, isGit);
                }
            } else if (!Files.exists(targetSubDir)) {
                Files.createDirectories(targetSubDir);
            }

            // 2. Déplacer chaque module enfant à l'intérieur du sous-projet
            for (ModuleReorganizationItem child : group.childModules()) {
                if (!child.included()) continue;
                Path origChildDir = rootPath.resolve(child.originalRelativePath());
                Path targetChildDir = targetSubDir.resolve(origChildDir.getFileName().toString());

                if (Files.exists(origChildDir) && !origChildDir.equals(targetChildDir)) {
                    safeMoveDirectory(rootPath, origChildDir, targetChildDir, isGit);
                }
            }

            // 3. Écrire le nouveau pom.xml du sous-projet
            Path subPomPath = targetSubDir.resolve("pom.xml");
            String originalSubPom = readFileContent(subPomPath);
            String revisedSubPom = computeRevisedSubProjectPom(originalSubPom, group);
            Files.writeString(subPomPath, revisedSubPom, StandardCharsets.UTF_8);
        }

        // 4. Écrire le nouveau pom.xml racine
        Path rootPomPath = rootPath.resolve("pom.xml");
        String originalRootPom = readFileContent(rootPomPath);
        String revisedRootPom = computeRevisedRootPom(originalRootPom, computedPlan);
        Files.writeString(rootPomPath, revisedRootPom, StandardCharsets.UTF_8);

        log.info("Réorganisation modulaire appliquée avec succès sur {}", rootPath);

        return new ModuleReorganizationPlan(
                computedPlan.parentPomPath(),
                computedPlan.groups(),
                computedPlan.unassignedModules(),
                computedPlan.rootPomDiff(),
                computedPlan.subProjectPomDiffs(),
                computedPlan.gitMoveCommands(),
                true,
                "✅ Réorganisation des sous-projets appliquée avec succès sur le disque !"
        );
    }

    private void safeMoveDirectory(Path rootPath, Path source, Path target, boolean isGit) throws IOException {
        if (!Files.exists(source)) return;

        if (Files.exists(target) && Files.isDirectory(target)) {
            // Si le dossier cible existe déjà, déplacer son contenu
            try (var stream = Files.list(source)) {
                for (Path p : stream.toList()) {
                    Path dest = target.resolve(p.getFileName());
                    Files.move(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.deleteIfExists(source);
            return;
        }

        if (isGit) {
            try {
                Path relSource = rootPath.relativize(source);
                Path relTarget = rootPath.relativize(target);
                ProcessBuilder pb = new ProcessBuilder("git", "mv", relSource.toString(), relTarget.toString());
                pb.directory(rootPath.toFile());
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    return;
                }
            } catch (Exception e) {
                log.warn("git mv échoué, repli sur Files.move : {}", e.getMessage());
            }
        }

        Files.createDirectories(target.getParent() != null ? target.getParent() : rootPath);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private boolean isPackagingModule(Path rootPath, String modDir) {
        if (modDir.toLowerCase().startsWith("packaging-") || modDir.toLowerCase().endsWith("-packaging")) {
            return true;
        }
        Path pomPath = rootPath.resolve(modDir).resolve("pom.xml");
        if (Files.exists(pomPath)) {
            String content = readFileContent(pomPath);
            return content.contains("<artifactId>packaging-") ||
                    (content.contains("<packaging>pom</packaging>") && content.contains("<dependencies>"));
        }
        return false;
    }

    private String computeTargetSubProjectDir(String pkgDir, List<String> allDirs) {
        String stripped = pkgDir.replaceAll("^(?i)packaging-", "").replaceAll("(?i)-packaging$", "");
        if (stripped.isBlank()) {
            stripped = "subproject";
        }
        if (allDirs != null && allDirs.contains(stripped)) {
            return stripped + "-app";
        }
        return stripped;
    }


    private boolean matchesPackagingDomain(String pkgDir, String modDir) {
        String pkgClean = pkgDir.replaceAll("^(?i)packaging-", "").replaceAll("(?i)-packaging$", "");
        String modClean = modDir.toLowerCase();
        return modClean.contains(pkgClean.toLowerCase()) || pkgClean.toLowerCase().contains(modClean);
    }

    private List<String> readDeclaredModulesFromPom(Path pomPath) {
        List<String> modules = new ArrayList<>();
        if (!Files.exists(pomPath)) return modules;
        String content = readFileContent(pomPath);
        Matcher matcher = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>").matcher(content);
        while (matcher.find()) {
            modules.add(matcher.group(1).trim());
        }
        return modules;
    }

    private String extractArtifactId(Path pomPath, String fallback) {
        if (!Files.exists(pomPath)) {
            return fallback;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(pomPath.toFile());
            Element root = doc.getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if ("artifactId".equals(children.item(i).getNodeName())) {
                    String val = children.item(i).getTextContent().trim();
                    if (!val.isBlank()) return val;
                }
            }
        } catch (Exception e) {
            String content = readFileContent(pomPath);
            String noParent = content.replaceAll("(?s)<parent>.*?</parent>", "");
            Matcher m = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>").matcher(noParent);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return fallback;
    }


    private List<String> extractReferencedArtifacts(Path pomPath) {
        List<String> deps = new ArrayList<>();
        if (!Files.exists(pomPath)) return deps;

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(pomPath.toFile());

            NodeList dependencies = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dep = (Element) dependencies.item(i);
                NodeList artList = dep.getElementsByTagName("artifactId");
                if (artList.getLength() > 0) {
                    deps.add(artList.item(0).getTextContent().trim());
                }
            }
        } catch (Exception e) {
            // Repli Regex
            String content = readFileContent(pomPath);
            Matcher m = Pattern.compile("<dependency>[\\s\\S]*?<artifactId>\\s*([^<\\s]+)\\s*</artifactId>[\\s\\S]*?</dependency>").matcher(content);
            while (m.find()) {
                deps.add(m.group(1).trim());
            }
        }
        return deps;
    }

    private String computeRevisedRootPom(String originalContent, ModuleReorganizationPlan plan) {
        if (originalContent == null || originalContent.isBlank()) return "";

        Set<String> removedModules = new HashSet<>();
        List<String> addedSubProjects = new ArrayList<>();

        for (PackagingSubProjectGroup g : plan.groups()) {
            if (!g.included()) continue;
            removedModules.add(g.originalPackagingDir());
            for (ModuleReorganizationItem child : g.childModules()) {
                if (child.included()) {
                    removedModules.add(child.originalRelativePath());
                }
            }
            if (!addedSubProjects.contains(g.targetSubProjectDir())) {
                addedSubProjects.add(g.targetSubProjectDir());
            }
        }

        // Remplacer les modules existants dans le bloc <modules>
        Matcher modulesMatcher = Pattern.compile("(?s)<modules>(.*?)</modules>").matcher(originalContent);
        if (!modulesMatcher.find()) {
            return originalContent;
        }

        String modulesBlock = modulesMatcher.group(1);
        List<String> retained = new ArrayList<>();
        Matcher itemMatcher = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>").matcher(modulesBlock);
        while (itemMatcher.find()) {
            String m = itemMatcher.group(1).trim();
            if (!removedModules.contains(m)) {
                retained.add(m);
            }
        }

        // Ajouter les nouveaux sous-projets
        for (String sub : addedSubProjects) {
            if (!retained.contains(sub)) {
                retained.add(sub);
            }
        }

        StringBuilder newModulesBuilder = new StringBuilder("\n");
        for (String r : retained) {
            newModulesBuilder.append("        <module>").append(r).append("</module>\n");
        }
        newModulesBuilder.append("    ");

        return originalContent.substring(0, modulesMatcher.start(1)) +
                newModulesBuilder +
                originalContent.substring(modulesMatcher.end(1));
    }

    private String computeRevisedSubProjectPom(String originalContent, PackagingSubProjectGroup group) {
        if (originalContent == null || originalContent.isBlank()) return "";

        List<String> childDirs = group.childModules().stream()
                .filter(ModuleReorganizationItem::included)
                .map(item -> Paths.get(item.originalRelativePath()).getFileName().toString())
                .toList();

        StringBuilder modulesXml = new StringBuilder("    <modules>\n");
        for (String c : childDirs) {
            modulesXml.append("        <module>").append(c).append("</module>\n");
        }
        modulesXml.append("    </modules>\n");

        // Si le POM a déjà un bloc <modules>, le remplacer
        Matcher mMatcher = Pattern.compile("(?s)<modules>.*?</modules>").matcher(originalContent);
        if (mMatcher.find()) {
            return originalContent.substring(0, mMatcher.start()) + modulesXml.toString().trim() + originalContent.substring(mMatcher.end());
        }

        // Assurer que le packaging est "pom" pour agréger des sous-modules
        String revised = originalContent;
        if (revised.contains("<packaging>jar</packaging>")) {
            revised = revised.replace("<packaging>jar</packaging>", "<packaging>pom</packaging>");
        } else if (!revised.contains("<packaging>pom</packaging>")) {
            // Insérer après <artifactId>
            revised = revised.replaceFirst("(<artifactId>.*?</artifactId>)", "$1\n    <packaging>pom</packaging>");
        }

        // Insérer <modules> avant <dependencies> ou avant </project>
        if (revised.contains("<dependencies>")) {
            return revised.replace("<dependencies>", modulesXml.toString() + "\n    <dependencies>");
        } else if (revised.contains("</project>")) {
            return revised.replace("</project>", modulesXml.toString() + "</project>");
        }

        return revised;
    }

    private String readFileContent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
