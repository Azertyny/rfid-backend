# Feature Specification: Tableau de bord (index.html)

**Feature Branch**: `N/A — reverse-engineered from codebase, not developed on a dedicated branch`

**Created**: 2026-09-24

**Status**: As-Is (reverse-engineered) — describes current behavior, not a target design

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

> [NEEDS CLARIFICATION] Aucune section de `doc/` ne décrit ce tableau de bord. Il n'existe que dans le code front (`front/index.html`) et l'API OpenAPI (`api.yaml`) qu'il consomme — cette spec compare donc le front à l'API plutôt qu'à un texte métier.

## Clarifications

### Session 2026-09-24

- Q: The dashboard calls GET /api/tags, which doesn't exist, so it never shows data; what it actually needs is today's scan events (Records). Should it be kept and backed by a real endpoint, or removed? → A: Keep the page, but do not add an endpoint for now — the dashboard stays in /front and in the deployment; its data source is deferred, so it keeps showing no production data until then.
- Q: index.html has a reader API token hardcoded in plain text (committed 2026-03-18, so also in git history and the deployed page). What should be done with it? → A: Remove and rotate (recommended) — remove it from index.html and treat it as compromised: regenerate that reader's token once the revoke/rotate route from spec 002 exists.
- Q: Once auth exists, the dashboard can no longer call /readers and /pickers anonymously. Who should be able to open the dashboard? → A: Opérateur and Admin (recommended) — any logged-in user with the Opérateur or Administrateur role; read-only.
- Q: Opérateurs need the reader list (dashboard line selector, reader.html), but GET /readers was planned Administrateur-only because it returns API tokens. How should Opérateurs get the list? → A: Hide tokens from Opérateurs (recommended) — GET /readers is open to both roles; tokens are only included for Administrateurs.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Visualiser l'activité en temps réel (Priority: P3 — fonctionnalité constatée non fonctionnelle, voir Edge Cases)

Un utilisateur ouvre `front/index.html` pour voir un tableau de bord : sélecteur de ligne, grille des cueilleurs actifs, graphique de cadence horaire, statut de connexion.

**Evidence**: `front/index.html:154-330`.

**Acceptance Scenarios**:

1. **Given** la page est chargée, **When** `fetchReaders()`/`fetchPickersInfo()`/`fetchData()` s'exécutent en boucle (polling), **Then** la page tente d'appeler `GET /api/readers`, `GET /api/pickers?size=500` et `GET /api/tags?size=3000&_t=<timestamp>`. Evidence: `front/index.html:177-226`.
2. **Given** `GET /api/tags` est appelé, **When** on compare à `api.yaml`, **Then** cette route **n'existe pas** dans la spécification OpenAPI ni dans `TagApiDelegate` généré (seules `POST /tags/buckets/{bucketNumber}` et `POST /tags/scan` existent, `api.yaml:156-214`). L'appel échouera systématiquement (404 / route non mappée). Voir Edge Cases.

### Edge Cases

- **Fonctionnalité non opérationnelle** : `processBusinessLogic(allTags)`, `updatePickersGrid(tags)`, `initChart()`/`updateChart(tags)` (`front/index.html:228-318`) dépendent tous du résultat de `fetchData()`, lui-même dépendant d'un endpoint `GET /api/tags` inexistant côté backend. En l'état actuel du dépôt, cette page ne peut pas afficher de données de tags/cadence réelles. Constat : les champs lus par `processBusinessLogic` (`processedAt`, `isCompliant`, `readerId`, `front/index.html:231-235`) sont ceux d'un événement de scan (`Record`), pas d'un `Tag` — la source attendue est donc un flux de lectures du jour, pas une liste de tags. **Résolu (2026-09-24)** : la page est conservée, mais aucun endpoint n'est ajouté pour l'instant ; la source de données est reportée à une itération ultérieure. D'ici là, la page reste sans données de production (statut "Erreur API") — état connu et accepté.
- **Jeton d'API codé en dur dans une page publique** : `const API_TOKEN = "176c77ca6757494f9729784263c6022d";` (`front/index.html:155`) est un secret embarqué en clair dans un fichier HTML statique, servi tel quel via `deploy/front` (configuration nginx `deploy/front/default.conf`) et le `Dockerfile`. Ce jeton correspond au format généré par `ReaderEntity.prePersist` (`ReaderEntity.java:45-50`, UUID sans tirets) — il s'agit donc vraisemblablement d'un vrai jeton de lecteur, pas d'un placeholder. Il est envoyé sur des routes qui ne le vérifient même pas aujourd'hui (`/readers`, `/pickers` sont `permitAll()`, voir spec `001`/`002`). Le jeton est présent dans l'historique git depuis le commit `c68fb98` (2026-03-18) et absent de la base H2 locale, ce qui suggère un jeton de lecteur de production. **Résolu (2026-09-24)** : retirer le jeton de `index.html` et le considérer comme compromis — le retirer du fichier ne suffit pas puisqu'il reste dans l'historique git ; le jeton du lecteur concerné DOIT être régénéré dès que la route de révocation/rotation de la spec `002` existe.
- **Accès à la page une fois l'authentification en place** : les deux appels qui fonctionnent aujourd'hui (`/readers`, `/pickers`) seront authentifiés (specs `001`, `002`). **Résolu (2026-09-24)** : la page est accessible à tout utilisateur connecté de rôle Opérateur ou Administrateur, en lecture seule. `GET /readers` est ouvert à ces deux rôles, mais les jetons ne sont renvoyés qu'aux Administrateurs (spec `002`) ; la page n'a pas besoin des jetons.
- **Pas de test** : aucun outillage de test front (pas de `package.json`, pas de suite JS) n'existe dans le dépôt pour vérifier ce fichier.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La page DOIT afficher la liste des lecteurs et des cueilleurs en interrogeant `GET /api/readers` et `GET /api/pickers?size=500`. Evidence: `front/index.html:177-215`.
- **FR-002**: La page TENTE d'interroger `GET /api/tags?size=3000` pour construire la grille et le graphique de cadence — endpoint absent de l'API réelle. Evidence: `front/index.html:217-226` vs `api.yaml` (absence de route `GET /tags`). **Décision (Clarifications 2026-09-24)** : aucun endpoint ajouté pour l'instant ; la page est conservée et reste sans données jusqu'à une itération ultérieure.
- **FR-003**: État actuel — la page envoie un jeton d'API codé en dur (`x-api-token`) sur chacun de ces appels. Evidence: `front/index.html:155,179,209,219`. **Décision (Clarifications 2026-09-24)** : la page NE DOIT PAS contenir de jeton ; le jeton exposé DOIT être régénéré via la rotation prévue en spec `002`.
- **FR-004** (nouveau, Clarifications 2026-09-24) : la page DOIT être accessible aux utilisateurs connectés de rôle Opérateur ou Administrateur, en lecture seule. Non implémenté aujourd'hui (aucune authentification).

### Key Entities

Aucune nouvelle entité — cette page ne fait que lire les entités `Reader`, `Picker` et (tenter de lire) `Tag` décrites dans les autres specs.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (reporté, Clarifications 2026-09-24) : aucun critère d'affichage de cadence/grille n'est fixé tant que la source de données n'est pas définie ; la page est conservée mais sa source de données est hors périmètre de l'itération actuelle.

## Assumptions

- **Confirmé (Clarifications 2026-09-24)** : cette page est conservée (pas du code mort à retirer), mais sa source de données est reportée ; elle reste non fonctionnelle d'ici là.

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| Aucune section `doc/` ne décrit de tableau de bord | Page complète existe en front, partiellement non fonctionnelle | `front/index.html` |
| — | Appel à un endpoint `GET /api/tags` absent de `api.yaml` | `front/index.html:219`, `api.yaml` (absence) |
| — | Jeton d'API en clair dans le code source front public ; cible (Clarifications 2026-09-24) = retrait et rotation du jeton | `front/index.html:155` |
| — | Appel à `GET /api/tags` : cible (Clarifications 2026-09-24) = page conservée, source de données reportée | `front/index.html:219` |
