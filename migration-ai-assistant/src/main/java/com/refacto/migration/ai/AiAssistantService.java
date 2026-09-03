package com.refacto.migration.ai;

import com.refacto.migration.core.model.Dependency;
import com.refacto.migration.core.model.DiffEntry;
import com.refacto.migration.core.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Service d'assistance IA pour explications de findings et aide à la décision (Sections 52 et 53).
 * STRICTEMENT restreint : l'IA ne modifie jamais directement le dépôt sans validation et passage par le moteur de diff.
 */
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    public String explainFinding(Finding finding) {
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

            default -> """
                    ### Explication de la règle %s
                    %s
                    
                    **Impact technique :** %s
                    **Action recommandée :** %s
                    """.formatted(finding.recipeId(), finding.description(), finding.technicalReason(), finding.suggestedAction());
        };
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
}
