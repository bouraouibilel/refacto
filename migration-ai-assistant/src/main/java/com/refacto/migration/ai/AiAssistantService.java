package com.refacto.migration.ai;

import com.refacto.migration.core.model.Dependency;
import com.refacto.migration.core.model.DiffEntry;
import com.refacto.migration.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service d'assistance IA pour explications de findings, aide au refactoring et dialogue interactif.
 * Combine un client LLM (Ollama, OpenAI, modèles locaux) avec une base de connaissances déterministe hors-ligne.
 * STRICTEMENT restreint : l'IA ne modifie jamais directement le code source sans validation humaine ou validation par tests.
 */
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    private final LlmClient llmClient;
    private LlmConfig config;

    public AiAssistantService() {
        this.llmClient = new LlmClient();
        this.config = LlmConfig.defaultOffline();
    }

    public AiAssistantService(LlmClient llmClient, LlmConfig config) {
        this.llmClient = llmClient != null ? llmClient : new LlmClient();
        this.config = config != null ? config : LlmConfig.defaultOffline();
    }

    public LlmConfig getConfig() {
        return config;
    }

    public synchronized void updateConfig(LlmConfig newConfig) {
        if (newConfig != null) {
            this.config = newConfig;
            log.info("Configuration LLM mise à jour : provider={}, model={}, enabled={}",
                    newConfig.provider(), newConfig.model(), newConfig.enabled());
        }
    }

    public boolean testLlmConnection() {
        return llmClient.testConnection(config);
    }

    /**
     * Dialogue interactif libre avec l'assistant de migration.
     */
    public String askAssistant(String userPrompt, String systemContext, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();

        String systemPrompt = """
                Tu es un Architecte Logiciel Senior spécialisé dans la migration d'applications Java legacy,
                la modernisation Spring Boot 3 / Spring Batch 5, Java 17+, et le refactoring architectural DDD.
                Tu fournis des explications claires, précises, étayées de justifications techniques et de snippets de code propres.
                Règles de déontologie :
                - Ne jamais inventer d'APIs inexistantes.
                - Privilégier les solutions standards (Spring Batch 5, Java 17 Stream API, Try-with-resources, décomposition DDD).
                - Si l'utilisateur demande une migration, lui expliquer les risques de régression.
                """;

        if (systemContext != null && !systemContext.isBlank()) {
            systemPrompt += "\n\nContexte d'analyse actuel :\n" + systemContext;
        }

        messages.add(ChatMessage.system(systemPrompt));

        if (history != null) {
            messages.addAll(history);
        }

        messages.add(ChatMessage.user(userPrompt));

        Optional<String> llmAnswer = llmClient.sendChatCompletion(config, messages);
        if (llmAnswer.isPresent()) {
            return llmAnswer.get();
        }

        // Fallback déterministe hors-ligne
        return generateOfflineResponse(userPrompt, systemContext);
    }

    /**
     * Explication approfondie d'un finding avec aide du LLM ou base heuristique.
     */
    public String explainFinding(Finding finding) {
        return explainFinding(finding, null);
    }

    public String explainFinding(Finding finding, String codeContext) {
        if (config.enabled()) {
            String prompt = String.format("""
                    Explique en détail le finding de migration suivant :
                    - Règle / Recipe ID : %s
                    - Fichier : %s (lignes %d - %d)
                    - Description : %s
                    - Raison technique : %s
                    - Impact de migration : %s
                    - Action suggérée : %s
                    - Extrait de code actuel :
                    ```java
                    %s
                    ```
                    %s
                    
                    Donne :
                    1. La cause profonde du problème.
                    2. Le risque fonctionnel ou de performance lors de la migration.
                    3. La solution recommandée étape par étape.
                    """,
                    finding.recipeId(),
                    finding.filePath(),
                    finding.startLine(),
                    finding.endLine(),
                    finding.description(),
                    finding.technicalReason(),
                    finding.migrationImpact(),
                    finding.suggestedAction(),
                    finding.codeSnippet() != null ? finding.codeSnippet() : "",
                    codeContext != null ? "\nContexte de code additionnel :\n" + codeContext : ""
            );

            Optional<String> llmAnswer = llmClient.sendChatCompletion(config, List.of(
                    ChatMessage.system("Tu es un expert en audit de code et refactoring de batchs Java legacy."),
                    ChatMessage.user(prompt)
            ));

            if (llmAnswer.isPresent()) {
                return llmAnswer.get();
            }
        }

        // Fallback déterministe
        return explainFindingOffline(finding);
    }

    /**
     * Suggestion de code refactoré pour un finding.
     */
    public String suggestRefactoring(Finding finding, String codeSnippet) {
        String snippet = (codeSnippet != null && !codeSnippet.isBlank()) ? codeSnippet : finding.codeSnippet();

        if (config.enabled() && snippet != null && !snippet.isBlank()) {
            String prompt = String.format("""
                    Propose une version refactorée et modernisée pour le code suivant conformément à la règle %s :
                    Action attendue : %s
                    
                    Code d'origine :
                    ```java
                    %s
                    ```
                    
                    Fournis uniquement le code Java refactoré complet avec des commentaires explicatifs et les imports nécessaires.
                    """,
                    finding.recipeId(),
                    finding.suggestedAction(),
                    snippet
            );

            Optional<String> llmAnswer = llmClient.sendChatCompletion(config, List.of(
                    ChatMessage.system("Tu es un compilateur et transformateur de code Java expert en refactoring moderne."),
                    ChatMessage.user(prompt)
            ));

            if (llmAnswer.isPresent()) {
                return llmAnswer.get();
            }
        }

        // Fallback déterministe
        return suggestRefactoringOffline(finding, snippet);
    }

    public String explainDependencyConflict(Dependency dependency) {
        return """
                ### Conflit de version détecté sur %s
                - **Versions actuellement utilisées :** `%s`
                - **Version cible recommandée :** `%s`
                - **Modules impactés :** %s
                
                **Pourquoi est-ce dangereux ?**
                Lors du packaging ou du déploiement, Maven résout une seule version sur le classpath. Le code compilé avec une autre version risque de provoquer des `NoSuchMethodError` ou `ClassNotFoundException` au runtime.
                
                **Action recommandée :**
                Harmoniser la version dans le `dependencyManagement` du POM racine parent ou via un BOM unique.
                """.formatted(dependency.getCoordinates(), dependency.currentVersion(), dependency.targetVersion(), String.join(", ", dependency.modules()));
    }

    public String explainDiff(DiffEntry diff) {
        return """
                ### Analyse du changement proposé sur %s
                - **Règle :** %s
                - **Niveau d'automatisation :** %s
                - **Explication :** %s
                
                Ce changement est soumis à validation humaine ou validation par tests unitaires.
                """.formatted(diff.filePath(), diff.recipeName(), diff.automationLevel(), diff.explanation());
    }

    private String explainFindingOffline(Finding finding) {
        return switch (finding.recipeId()) {
            case "APP-DB-001" -> """
                    ### Explication de l'accès DB hors DAO (APP-DB-001)
                    La classe `%s` effectue des opérations directes avec la base de données via `%s`.
                    
                    **Pourquoi est-ce dangereux ?**
                    - Dispersion de la logique de requêtage et couplage fort entre traitement métier/batch et base de données.
                    - Empêche l'application de stratégies de cache, de logging centralisé et d'optimisations de requêtes.
                    
                    **Action recommandée :**
                    Déplacer la logique d'accès aux données vers un composant DAO dédié et injecter ce DAO dans la classe appelante.
                    """.formatted(finding.moduleName(), finding.symbol());

            case "APP-DB-003" -> """
                    ### Explication du verrouillage SELECT FOR UPDATE (APP-DB-003)
                    La requête `%s` pose un verrou exclusif sur les lignes sélectionnées.
                    
                    **Pourquoi est-ce dangereux ?**
                    - Sans clause `ORDER BY` déterministe sur les clés primaires, deux batchs ou threads parallèles accédant aux mêmes données dans un ordre différent provoqueront des **deadlocks** (interblocages ORA-00060).
                    - L'utilisation de `SKIP LOCKED` permet d'ignorer les lignes déjà verrouillées par une autre instance, mais n'est acceptable que si le report du traitement est fonctionnellement permis.
                    
                    **Action recommandée :**
                    Vérifier le déterminisme du tri et limiter la taille du lot et la durée de transaction.
                    """.formatted(finding.codeSnippet());

            case "APP-DB-004" -> """
                    ### Explication des appels DB en boucle (APP-DB-004)
                    Une interaction avec la persistance est exécutée à l'intérieur d'une boucle (`for`/`while`/`stream`).
                    
                    **Impact volumétrique estimé :**
                    ~ %s allers-retours réseau (round-trips) potentiels vers le serveur de base de données.
                    
                    **Pourquoi est-ce dangereux ?**
                    - Chaque requête réseau consomme du temps de latence (round-trip time ~ 1-5ms) et mobilise une connexion du pool.
                    - Sur 10 000 éléments, 10 000 requêtes unitaires peuvent prendre 30 minutes là où une seule requête par lot prendrait 200 ms.
                    
                    **Action recommandée :**
                    Remplacer par un chargement ou une mise à jour par lot (`findAllById`, `saveAll` ou JDBC Batch).
                    """.formatted(finding.potentialRoundTrips() != null ? finding.potentialRoundTrips() : "Plusieurs milliers de");

            case "APP-ARCH-001" -> """
                    ### Explication du composant métier partagé (APP-ARCH-001)
                    Le composant `%s` est partagé entre plusieurs modules ou batchs sans frontière d'isolation.
                    
                    **Pourquoi est-ce dangereux ?**
                    - Toute modification de schéma ou de règle impacte l'ensemble des batchs consommateurs (effet domino).
                    - Empêche une montée de version indépendante et viole les principes d'architecture hexagonale et DDD.
                    
                    **Action recommandée :**
                    Scinder le composant par Bounded Context dédié et communiquer via des DTOs ou identifiants.
                    """.formatted(finding.symbol());

            case "JAVA17-003" -> """
                    ### Explication de la boucle imbriquée (JAVA17-003)
                    Boucle `for` imbriquée avec accumulateur mutable détectée.
                    
                    **Pourquoi est-ce améliorable ?**
                    - Complexité cyclomatique élevée et code impératif difficile à tester unitairement.
                    - La syntaxe Java 17 Stream API avec `flatMap` et `toList()` élimine les variables mutables et permet un traitement déclaratif.
                    
                    **Action recommandée :**
                    Remplacer par `outerCollection.stream().flatMap(...).filter(...).toList()`.
                    """;

            default -> """
                    ### Explication de la règle %s
                    %s
                    
                    **Impact technique :** %s
                    **Action recommandée :** %s
                    """.formatted(finding.recipeId(), finding.description(), finding.technicalReason(), finding.suggestedAction());
        };
    }

    private String suggestRefactoringOffline(Finding finding, String codeSnippet) {
        return switch (finding.recipeId()) {
            case "APP-DB-001" -> """
                    // Solution recommandée : Déplacer l'accès base dans un DAO
                    @Repository
                    public class PaymentDAO {
                        @PersistenceContext
                        private EntityManager entityManager;
                        
                        public PaymentEntity findById(Long id) {
                            return entityManager.find(PaymentEntity.class, id);
                        }
                    }
                    """;

            case "JAVA17-001" -> """
                    // Modernisation Try-with-resources automatique
                    try (FileInputStream fis = new FileInputStream(file)) {
                        fis.read();
                    }
                    """;

            case "JAVA17-003" -> """
                    // Modernisation Stream API
                    return paymentBatches.stream()
                            .flatMap(List::stream)
                            .filter(p -> p.getAmount() != null && p.getAmount().compareTo(new BigDecimal("1000")) > 0)
                            .toList();
                    """;

            case "APP-BATCH-001", "APP-BATCH-002" -> """
                    // Modernisation Spring Batch 5
                    new JobBuilder("paymentJob", jobRepository)
                            .start(step1)
                            .build();
                    """;

            default -> "// Proposition de refactoring pour " + finding.recipeId() + " :\n" +
                    "// Action : " + finding.suggestedAction() + "\n" +
                    (codeSnippet != null ? codeSnippet : "");
        };
    }

    private String generateOfflineResponse(String userPrompt, String systemContext) {
        String lower = userPrompt.toLowerCase();
        if (lower.contains("ddd") || lower.contains("bounded context") || lower.contains("architecture")) {
            return """
                    ### Recommandation d'Architecture DDD (Domain-Driven Design)
                    Pour simplifier et découpler votre projet :
                    1. **Identifier les Bounded Contexts** : Séparer les domaines autonomes (ex: Paiement vs Client).
                    2. **Découpler les modèles partagés** : Remplacer les entités partagées par des identifiants (`customerId` au lieu de `CustomerEntity`).
                    3. **Isoler les couches** :
                       - **Domain** : Entités métier pures et interfaces sans Spring ni Hibernate.
                       - **Application** : Orchestration des traitements batch.
                       - **Infrastructure** : Readers/Writers Spring Batch 5 et persistence JPA.
                    
                    👉 Consultez le nouvel onglet **"Architecture DDD"** pour visualiser la décomposition projet proposée.
                    """;
        }
        if (lower.contains("batch") || lower.contains("spring 5") || lower.contains("spring batch")) {
            return """
                    ### Migration vers Spring Batch 5
                    Les changements clés de Spring Batch 5 (Spring Boot 3) :
                    1. Suppression de `JobBuilderFactory` et `StepBuilderFactory` au profit des constructeurs directs `new JobBuilder(name, jobRepository)`.
                    2. Obligation d'injecter explicitement `JobRepository` et `PlatformTransactionManager`.
                    3. Passage de `javax.batch` / `javax.persistence` à `jakarta.batch` / `jakarta.persistence`.
                    """;
        }
        return """
                ### Assistant de Migration Refacto
                Je peux vous aider à :
                - Expliquer en détail un finding technique ou un risque de régression.
                - Proposer du code refactoré en Java 17 et Spring Batch 5.
                - Vous guider dans la transition vers une architecture DDD en Bounded Contexts.
                
                *Astuce : Vous pouvez configurer un LLM local (ex: Ollama) dans les réglages de l'assistant pour des réponses encore plus dynamiques.*
                """;
    }
}
