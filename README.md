# Plateforme d'Analyse et de Migration Technique d'Applications Java

Plateforme complète d'analyse, d'explication, de planification, de transformation et de validation pour la modernisation technique d'applications Java patrimoniales et de traitements Spring Batch.

Conçue et implémentée selon le **Cahier des Charges Technique (Version 1.0)** respectant le principe fondamental :  
**« Analyze → Explain → Plan → Transform → Validate »**.

---

## 🚀 Fonctionnalités Clés

### 1. Analyse & Découverte Automatisée
- **Découverte Maven Multi-modules :** Résolution récursive des arborescences de POM, héritage `parent`/`submodule`, résolution des versions managées (`<dependencyManagement>`), interpolation des propriétés et détection automatique des frameworks (Spring Boot, Spring Batch, JPA, Hibernate, JUnit 4/5, Oracle JDBC).
- **Inspection Spring Batch (AST) :** Analyse fine des configurations `@EnableBatchProcessing`, détection des `Job`, `Step`, découpage `chunk(size)`, `reader`, `processor`, `writer`, `tasklet`, `listeners` et déclencheurs `@Scheduled`.
- **Inventaire & Conflits de Dépendances :** Cartographie des dépendances directes et transitives, détection des conflits de versions inter-modules et calcul des breaking changes (Spring Batch 4 -> 5, Hibernate 5 -> 6, Java 11 -> 17, `javax.*` -> `jakarta.*`).

### 2. Moteurs d'Analyse Spécialisés
- **Analyse de Code & SRP (`APP-CODE`) :**
  - `APP-CODE-001` : Détection des classes monolithiques cumulant de multiples responsabilités (persistance, validation métier, I/O, alertes) avec proposition de décomposition.
  - `APP-CODE-002` : Contrôle des niveaux d'abstraction dans les méthodes principales batch (`process`, `execute`, `read`, `write`).
  - `JAVA17-*` : Modernisation Java standard (`try-with-resources`, `java.nio.file.Path`, interdiction des `Optional` en attributs persistants JPA).
- **Analyse d'Architecture & Découplage (`APP-ARCH`) :**
  - `APP-ARCH-001` : Détection et scoring de couplage des composants métier partagés transverses (DAO, Entités, Services).
  - `APP-ARCH-002` : Détection des utilitaires techniques purs éligibles à l'externalisation dans `APP-commons` (en excluant formellement tout composant métier).
- **Analyse SQL & Concurrence (`APP-DB-001` à `APP-DB-007`) :**
  - `APP-DB-001` : Détection des accès directs DB (`EntityManager`, `JdbcTemplate`, `Connection`) hors DAO.
  - `APP-DB-002` : Détection des traitements de masse JPA en boucle recommandant du SQL direct (`UPDATE`/`INSERT`/`MERGE`/`DELETE`).
  - `APP-DB-003` : Analyse approfondie des `SELECT ... FOR UPDATE`, déterminisme de l'ordonnancement (`ORDER BY`) et pertinence de `SKIP LOCKED`.
  - `APP-DB-004` : Détection des requêtes DB dans les boucles (`for`, `while`, `forEach`) et calcul des round-trips réseau potentiels.
  - `APP-DB-005` : Élimination du `SELECT *` et recommandation de projections ciblées (exécuté obligatoirement après `APP-DB-002`).
  - `APP-DB-006` : Contrôle des sauvegardes unitaires dans les writers de chunk.
  - `APP-DB-007` : Qualité SQL (fonctions sur colonnes filtrées, `COUNT(*)` vs `EXISTS`).

### 3. Moteur de Recettes, Graphe de Dépendances (DAG) & Score de Risque
- **Catalogue de Recettes Déclaratif :** Recettes YAML structurées avec tags, sévérités, préconditions et validations obligatoires.
- **Planificateur DAG (Tri Topologique) :** Algorithme de Kahn garantissant le respect strict des dépendances (ex: `APP-DB-005` dépendant de `APP-DB-002`) et détection immédiate des cycles (`CircularDependencyException`).
- **Ordonnancement en Vagues :** Organisation logique de la migration en 8 vagues (Vague 0 : Baseline -> Vague 7 : Découplage et commons).
- **Calculateur de Score de Risque (0 à 100) :** Scoring transparent et déterministe avec justification détaillée de chaque facteur (APIs cassées, conflits, requêtes verrouillantes, couplage transverse).

### 4. Transformation Sécurisée, Dry-Run & Diff Viewer
- **Niveaux d'Automatisation Stricts :** `AUTO_SAFE`, `AUTO_WITH_TESTS`, `REVIEW_REQUIRED`, `MANUAL_ONLY`.
- **Exécution Dry-Run en Mémoire :** Génération de diffs unifiés (`--- a/... +++ b/...`) sans altération du disque.
- **Workflow de Revue & Approbation :** Interface de validation individuelle ou par lot, avec possibilité de rejet argumenté.

### 5. Double Validation & Performance
- **Validation Build & Tests :** Exécution automatisée des compilations et suites de tests Maven.
- **Double Validation (Performance + Fonctionnelle) :** Un traitement n'est validé que si son gain de temps s'accompagne d'une empreinte fonctionnelle identique (`PERFORMANCE PASS` + `FUNCTIONAL PASS`).

### 6. Rapports Multi-formats & Assistant IA
- **Génération de Rapports :** Formats HTML interactif (tableaux de bord, KPIs colorés), Markdown et JSON.
- **Assistant IA Contextuel :** Fournit des explications pédagogiques sur les constats, les conflits et les propositions de code sans jamais court-circuiter le moteur de règles.
- **Dashboard Web Moderne :** Interface monopage (Tailwind CSS, Lucide Icons) accessible à l'adresse `http://localhost:8085/`.

---

## 🏗️ Architecture Modulaire

```
refacto/
├── pom.xml                               # POM parent multi-modules (Java 21, Spring Boot 3.3.4)
├── migration-core-model/                 # Modèle de domaine immuable (Records, Enums)
├── migration-project-discovery/          # Découverte Maven & inspection AST Spring Batch
├── migration-dependency-analyzer/        # Analyse des dépendances, conflits et versions cibles
├── migration-code-analyzer/              # Analyseurs AST (SRP, abstraction, Java 17, architecture)
├── migration-sql-analyzer/               # Analyseurs SQL, persistance et concurrence (JSqlParser + AST)
├── migration-recipe-engine/              # Catalogue YAML, DAG topologique et score de risque
├── migration-transformation-engine/      # Générateur de diffs, moteur AST et Dry-Run
├── migration-validation-engine/          # Validation de build et exécution de tests
├── migration-performance-engine/         # Baselines, gains volumétriques et double validation
├── migration-reporting-engine/           # Génération de rapports HTML, Markdown et JSON
├── migration-ai-assistant/               # Assistant d'explication et d'aide à la décision
├── migration-app/                        # Application Spring Boot REST API & Web Dashboard
└── sample-legacy-app/                    # Application legacy témoin multi-modules pour les tests E2E
    ├── pom.xml
    ├── payment-model/                    # Entités JPA legacy (javax.persistence, Optional)
    ├── legacy-common/                    # Utilitaires & DAO partagé (couplage transverse)
    └── batch-payment/                    # Job Spring Batch 4.3 avec anomalies SQL et SRP
```

---

## 🛠️ Démarrage Rapide

### Prérequis
- Java 21 LTS
- Maven 3.9+

### 1. Compilation et exécution de l'ensemble des tests
```bash
mvn clean test
```

### 2. Installation des modules
```bash
mvn install -DskipTests
```

### 3. Lancement de l'application
```bash
mvn spring-boot:run -pl migration-app
```

Accédez ensuite au dashboard interactif sur : **`http://localhost:8085/`**.

---

## 📊 Endpoints REST Principaux

| Méthode | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/projects` | Enregistre un projet à analyser |
| `GET` | `/api/projects` | Liste les projets enregistrés |
| `POST` | `/api/projects/{id}/analyses` | Déclenche l'analyse complète (Discovery, AST, SQL, DAG, Dry-Run) |
| `GET` | `/api/analyses/{id}` | Récupère le contexte complet d'analyse |
| `GET` | `/api/analyses/{id}/findings` | Liste les constats techniques détectés |
| `GET` | `/api/analyses/{id}/waves` | Récupère le plan de migration ordonnancé en vagues (DAG) |
| `GET` | `/api/analyses/{id}/dry-run` | Récupère les diffs unifiés générés en mémoire |
| `POST` | `/api/analyses/{id}/diffs/{diffId}/approve` | Approuve une modification pour application |
| `GET` | `/api/analyses/{id}/report/html` | Exporte le rapport exécutif au format HTML |
| `GET` | `/api/analyses/{id}/report/markdown` | Exporte le rapport au format Markdown |
| `GET` | `/api/recipes` | Liste le catalogue complet des recettes |
| `POST` | `/api/ai/explain-finding` | Demande une explication IA sur une règle ou un constat |

---

## 📄 Licence
Projet réalisé selon les spécifications du Cahier des Charges Technique de Migration Java.
