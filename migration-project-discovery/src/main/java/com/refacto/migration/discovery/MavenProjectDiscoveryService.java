package com.refacto.migration.discovery;

import com.refacto.migration.core.enums.BuildSystem;
import com.refacto.migration.core.model.ModuleDescriptor;
import com.refacto.migration.core.model.RepositorySnapshot;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service de découverte de projet Maven multi-modules.
 * Analyse les pom.xml, la hiérarchie parent/enfant, les dépendances et les métriques de code.
 */
public class MavenProjectDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(MavenProjectDiscoveryService.class);

    public RepositorySnapshot discoverSnapshot(Path projectRoot, String branch, String commit) {
        BuildSystem buildSystem = detectBuildSystem(projectRoot);
        String javaVersion = detectProjectJavaVersion(projectRoot);

        return new RepositorySnapshot(
                commit != null ? commit : "HEAD",
                branch != null ? branch : "main",
                Instant.now(),
                buildSystem,
                javaVersion,
                projectRoot.toAbsolutePath().toString()
        );
    }

    public BuildSystem detectBuildSystem(Path projectRoot) {
        if (Files.exists(projectRoot.resolve("mvnw")) || Files.exists(projectRoot.resolve("mvnw.cmd"))) {
            return BuildSystem.MAVEN_WRAPPER;
        } else if (Files.exists(projectRoot.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        } else if (Files.exists(projectRoot.resolve("gradlew")) || Files.exists(projectRoot.resolve("gradlew.bat"))) {
            return BuildSystem.GRADLE_WRAPPER;
        } else if (Files.exists(projectRoot.resolve("build.gradle")) || Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        return BuildSystem.UNKNOWN;
    }

    public List<ModuleDescriptor> discoverModules(Path projectRoot) {
        List<ModuleDescriptor> modules = new ArrayList<>();
        Path rootPom = projectRoot.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            log.warn("Aucun pom.xml trouvé à la racine : {}", projectRoot);
            return modules;
        }

        Map<String, String> globalProperties = new HashMap<>();
        Map<String, String> managedVersions = new HashMap<>();
        discoverModuleRecursive(projectRoot, rootPom, projectRoot, globalProperties, managedVersions, modules);

        // Resolve inter-module dependencies
        resolveDependentModules(modules);
        return modules;
    }

    private void discoverModuleRecursive(Path projectRoot, Path pomPath, Path moduleDir,
                                         Map<String, String> inheritedProps,
                                         Map<String, String> inheritedManagedVersions,
                                         List<ModuleDescriptor> result) {
        try {
            Document doc = parseXml(pomPath);
            Element root = doc.getDocumentElement();

            // Extract module properties
            Map<String, String> properties = new HashMap<>(inheritedProps);
            extractProperties(root, properties);

            // Extract dependencyManagement
            Map<String, String> managedVersions = new HashMap<>(inheritedManagedVersions);
            extractDependencyManagement(root, properties, managedVersions);

            // GroupId, ArtifactId, Version
            String groupId = getTagValue(root, "groupId");
            String artifactId = getTagValue(root, "artifactId");
            String version = getTagValue(root, "version");
            String packaging = Optional.ofNullable(getTagValue(root, "packaging")).orElse("jar");

            // Fallback to parent groupId/version if omitted
            Element parentEl = getChildElement(root, "parent");
            if (parentEl != null) {
                if (groupId == null) groupId = getTagValue(parentEl, "groupId");
                if (version == null) version = getTagValue(parentEl, "version");
            }

            // Java version detection
            String javaVersion = resolveJavaVersion(properties, root);

            // Count Java sources and tests
            int sourceCount = countJavaFiles(moduleDir.resolve("src/main/java"));
            int testCount = countJavaFiles(moduleDir.resolve("src/test/java"));

            // Parse direct dependencies
            List<String> dependencies = extractDependencies(root, properties, managedVersions);

            // Framework detection
            Set<String> frameworks = detectFrameworks(dependencies, root);
            boolean hasSpringBatch = frameworks.contains("Spring Batch");
            boolean hasJpa = frameworks.contains("JPA") || frameworks.contains("Hibernate");

            String relativePath = projectRoot.relativize(moduleDir).toString().replace('\\', '/');
            if (relativePath.isEmpty()) {
                relativePath = ".";
            }

            ModuleDescriptor module = new ModuleDescriptor(
                    artifactId != null ? artifactId : moduleDir.getFileName().toString(),
                    artifactId != null ? artifactId : moduleDir.getFileName().toString(),
                    relativePath,
                    groupId != null ? groupId : "unknown",
                    artifactId != null ? artifactId : "unknown",
                    version != null ? version : "1.0.0",
                    packaging,
                    javaVersion,
                    frameworks,
                    dependencies,
                    new ArrayList<>(),
                    sourceCount,
                    testCount,
                    hasSpringBatch,
                    hasJpa
            );

            result.add(module);

            // Check submodules
            Element modulesEl = getChildElement(root, "modules");
            if (modulesEl != null) {
                NodeList moduleNodes = modulesEl.getElementsByTagName("module");
                for (int i = 0; i < moduleNodes.getLength(); i++) {
                    String subModuleName = moduleNodes.item(i).getTextContent().trim();
                    Path subModuleDir = moduleDir.resolve(subModuleName);
                    Path subModulePom = subModuleDir.resolve("pom.xml");
                    if (Files.exists(subModulePom)) {
                        discoverModuleRecursive(projectRoot, subModulePom, subModuleDir, properties, managedVersions, result);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erreur lors de la lecture du POM {} : {}", pomPath, e.getMessage());
        }
    }

    private void resolveDependentModules(List<ModuleDescriptor> modules) {
        Map<String, ModuleDescriptor> moduleByArtifact = new HashMap<>();
        for (ModuleDescriptor mod : modules) {
            moduleByArtifact.put(mod.artifactId(), mod);
        }

        for (int i = 0; i < modules.size(); i++) {
            ModuleDescriptor current = modules.get(i);
            List<String> dependents = new ArrayList<>();

            for (ModuleDescriptor other : modules) {
                if (!other.artifactId().equals(current.artifactId())) {
                    for (String dep : other.dependencies()) {
                        if (dep.contains(":" + current.artifactId() + ":") || dep.endsWith(":" + current.artifactId())) {
                            dependents.add(other.artifactId());
                            break;
                        }
                    }
                }
            }

            if (!dependents.isEmpty()) {
                modules.set(i, new ModuleDescriptor(
                        current.id(),
                        current.name(),
                        current.relativePath(),
                        current.groupId(),
                        current.artifactId(),
                        current.version(),
                        current.packaging(),
                        current.javaVersion(),
                        current.frameworks(),
                        current.dependencies(),
                        dependents,
                        current.sourceCount(),
                        current.testCount(),
                        current.hasSpringBatch(),
                        current.hasJpa()
                ));
            }
        }
    }

    private String detectProjectJavaVersion(Path projectRoot) {
        Path rootPom = projectRoot.resolve("pom.xml");
        if (Files.exists(rootPom)) {
            try {
                Document doc = parseXml(rootPom);
                Element root = doc.getDocumentElement();
                Map<String, String> properties = new HashMap<>();
                extractProperties(root, properties);
                return resolveJavaVersion(properties, root);
            } catch (Exception ignored) {
            }
        }
        return "11";
    }

    private String resolveJavaVersion(Map<String, String> properties, Element root) {
        if (properties.containsKey("java.version")) return properties.get("java.version");
        if (properties.containsKey("maven.compiler.source")) return properties.get("maven.compiler.source");
        if (properties.containsKey("maven.compiler.target")) return properties.get("maven.compiler.target");
        if (properties.containsKey("maven.compiler.release")) return properties.get("maven.compiler.release");
        return "11"; // Baseline default
    }

    private int countJavaFiles(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            return (int) stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private List<String> extractDependencies(Element root, Map<String, String> properties, Map<String, String> managedVersions) {
        List<String> result = new ArrayList<>();
        Element depsEl = getChildElement(root, "dependencies");
        if (depsEl != null) {
            NodeList deps = depsEl.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                Node node = deps.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element dep = (Element) node;
                    String g = resolveProperty(getTagValue(dep, "groupId"), properties);
                    String a = resolveProperty(getTagValue(dep, "artifactId"), properties);
                    String v = resolveProperty(getTagValue(dep, "version"), properties);
                    if (v == null && g != null && a != null) {
                        v = managedVersions.getOrDefault(g + ":" + a, "managed");
                    }
                    if (g != null && a != null) {
                        result.add(g + ":" + a + ":" + (v != null ? v : "managed"));
                    }
                }
            }
        }
        return result;
    }

    private void extractDependencyManagement(Element root, Map<String, String> properties, Map<String, String> managedVersions) {
        Element depMgmtEl = getChildElement(root, "dependencyManagement");
        if (depMgmtEl != null) {
            Element depsEl = getChildElement(depMgmtEl, "dependencies");
            if (depsEl != null) {
                NodeList deps = depsEl.getElementsByTagName("dependency");
                for (int i = 0; i < deps.getLength(); i++) {
                    Node node = deps.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element dep = (Element) node;
                        String g = resolveProperty(getTagValue(dep, "groupId"), properties);
                        String a = resolveProperty(getTagValue(dep, "artifactId"), properties);
                        String v = resolveProperty(getTagValue(dep, "version"), properties);
                        if (g != null && a != null && v != null) {
                            managedVersions.put(g + ":" + a, v);
                        }
                    }
                }
            }
        }
    }

    private Set<String> detectFrameworks(List<String> dependencies, Element root) {
        Set<String> frameworks = new HashSet<>();
        for (String dep : dependencies) {
            if (dep.contains("spring-boot")) frameworks.add("Spring Boot");
            if (dep.contains("spring-batch")) frameworks.add("Spring Batch");
            if (dep.contains("hibernate") || dep.contains("persistence")) frameworks.add("Hibernate");
            if (dep.contains("javax.persistence") || dep.contains("jakarta.persistence")) frameworks.add("JPA");
            if (dep.contains("junit:junit")) frameworks.add("JUnit 4");
            if (dep.contains("junit-jupiter")) frameworks.add("JUnit 5");
            if (dep.contains("ojdbc") || dep.contains("oracle")) frameworks.add("Oracle JDBC");
        }
        return frameworks;
    }

    private void extractProperties(Element root, Map<String, String> props) {
        Element propsEl = getChildElement(root, "properties");
        if (propsEl != null) {
            NodeList children = propsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    props.put(n.getNodeName(), n.getTextContent().trim());
                }
            }
        }
    }

    private String resolveProperty(String val, Map<String, String> props) {
        if (val == null) return null;
        if (val.startsWith("${") && val.endsWith("}")) {
            String key = val.substring(2, val.length() - 1);
            return props.getOrDefault(key, val);
        }
        return val;
    }

    private Document parseXml(Path xmlPath) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        try (InputStream is = new FileInputStream(xmlPath.toFile())) {
            return db.parse(is);
        }
    }

    private Element getChildElement(Element parent, String tagName) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(tagName)) {
                return (Element) n;
            }
        }
        return null;
    }

    private String getTagValue(Element parent, String tagName) {
        Element el = getChildElement(parent, tagName);
        return el != null ? el.getTextContent().trim() : null;
    }
}
