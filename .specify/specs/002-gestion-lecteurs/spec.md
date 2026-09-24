# Feature Specification: Gestion des lecteurs RFID (Reader registration)

**Feature Branch**: `N/A — reverse-engineered from codebase, not developed on a dedicated branch`

**Created**: 2026-09-24

**Status**: As-Is (reverse-engineered) — describes current behavior, not a target design

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

> [NEEDS CLARIFICATION] Ce module n'a **aucune** section dédiée dans `doc/20251116-use_cases.md` (seul `doc/20241103-principle_diagram.puml` mentionne un acteur `ClientRFID` de façon très générale). Toute la spécification ci-dessous est donc reconstruite uniquement à partir du code et de l'API, sans texte métier de référence à comparer.

## Clarifications

### Session 2026-09-24

- Q: GET /api/readers is unauthenticated and returns every reader's API token in plain text — the same tokens that protect /tags/scan. How should reader tokens be handled? → A: Keep in list, behind auth — GET /readers keeps returning apitoken, but the route requires authentication (same auth system decided as target in spec 001).
- Q: There's no route to update, revoke, or delete a reader once created. Should the target design add a way to revoke/rotate a reader's token? → A: Add revoke/rotate (recommended) — add an endpoint to regenerate a reader's token and/or delete a reader.
- Q: Creating a reader with a uid that's already taken currently falls through to a raw database constraint violation instead of an explicit 409. Should reader creation get the same explicit duplicate-check treatment as picker creation? → A: Yes, return 409 (recommended) — add an explicit uniqueness check before insert, mirroring PickerService.ensureUniqueName.
- Q (asked during spec 007): Opérateurs need the reader list (dashboard, reader.html), but GET /readers was planned Administrateur-only because it returns API tokens. How should Opérateurs get the list? → A: Hide tokens from Opérateurs — GET /readers is open to Opérateur and Administrateur; apitoken is only included for Administrateurs.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enregistrer un lecteur RFID (Priority: P1)

Un administrateur enregistre un nouveau lecteur en fournissant son identifiant (`uid`) ; le système génère et retourne un jeton d'API.

**Evidence**: `controller/ReaderController.java:20-23`, `service/ReaderService.java:20-30`, `front/readers.html:206-241` (`createReader`).

**Acceptance Scenarios**:

1. **Given** un `uid` non vide, **When** `POST /api/readers` est appelé, **Then** le système crée un `ReaderEntity`, génère un `apitoken` (UUID sans tirets, 32 caractères hexadécimaux) via `@PrePersist`, et renvoie `201` avec `uid` + `apitoken`. Evidence: `entity/ReaderEntity.java:45-50`, `ReaderService.java:20-30`.
2. **Given** un `uid` déjà utilisé (contrainte `unique = true` sur `ReaderEntity.name`), **When** `POST /api/readers` est rejoué, **Then** — comportement actuel — aucune vérification applicative d'unicité n'existe avant l'insertion (contrairement à `PickerService.ensureUniqueName`) ; l'échec remonterait comme une `DataIntegrityViolationException` non interceptée par un `@ExceptionHandler` dédié, probablement traduite en `500` générique — alors que `api.yaml:43-51` ne documente que `400`/`500` pour cette route (pas de `409`, contrairement à `POST /pickers`). Comportement non testé. Evidence: `entity/ReaderEntity.java:31`, absence de contrôleur d'exception `@ControllerAdvice` dans le dépôt. **Résolu (2026-09-24)** : cible = vérification explicite d'unicité avant insertion (même pattern que `PickerService.ensureUniqueName`), renvoyant `409` plutôt qu'un `500` générique.

---

### User Story 2 - Lister les lecteurs (Priority: P1)

Un client liste tous les lecteurs enregistrés, avec leur jeton d'API en clair.

**Evidence**: `ReaderController.java:25-29`, `ReaderService.java:32-46`, `front/readers.html:156-175`, `front/reader.html:123-154` (sélection d'un lecteur), `front/index.html:177-189`.

**Acceptance Scenarios**:

1. **Given** des lecteurs existent, **When** `GET /api/readers` est appelé, **Then** la réponse liste tous les lecteurs avec `uid`, `apitoken`, `creationDate`, `updateDate` — **sans pagination** (contrairement au module cueilleurs). Evidence: `ReaderService.java:32-46`, `api.yaml:427-435` (`ReadersList`, pas de `PageMetadata`).
2. **Given** cette route est appelée par n'importe quel client, **When** la réponse est reçue, **Then** — comportement actuel — elle contient le jeton d'API (`apitoken`) de **tous** les lecteurs en clair. Voir Edge Cases. **Résolu (2026-09-24)** : cible = route authentifiée (rôle Opérateur ou Administrateur) ; `apitoken` n'est renvoyé qu'aux Administrateurs (voir FR-003).

### Edge Cases

- **Fuite de jetons d'API** : `GET /api/readers` n'exige aucune authentification (`SecurityConfig.java:32-36` : seule `/api/tags/scan` est protégée). Or c'est précisément l'`apitoken` renvoyé par cette route qui sert de secret pour s'authentifier sur `/api/tags/scan` (`security/ReaderApiTokenAuthenticationFilter.java:49-59`). N'importe quel appelant non authentifié peut donc lister et récupérer les jetons de tous les lecteurs. `front/index.html:155` va plus loin et **code en dur** un jeton (`API_TOKEN = "176c77ca6757494f9729784263c6022d"`) dans une page HTML statique servie publiquement (`deploy/front`, `Dockerfile`). **Résolu (2026-09-24)** : faille à corriger — `GET /readers` doit devenir authentifié (Opérateur ou Administrateur), et les jetons ne doivent être renvoyés qu'aux Administrateurs. Le jeton codé en dur dans `front/index.html:155` doit être retiré et considéré comme compromis : il sera le premier à régénérer une fois la rotation disponible (voir spec `007`, Clarifications 2026-09-24).
- **Pas de mise à jour ni de suppression** : aucune route `PUT`/`DELETE` pour `/readers` n'existe dans `api.yaml` ni dans `ReaderApiDelegate` généré. Un jeton compromis ne peut donc pas être révoqué ni régénéré via l'API. **Résolu (2026-09-24)** : manquant — la cible ajoute une révocation/rotation de jeton et/ou une suppression de lecteur.
- **Incohérence de nommage `uid` / `name`** : l'API et le diagramme de base de données (`doc/20251013-database_diagram.puml:57`) nomment ce champ `uid`, mais l'entité JPA le stocke dans une colonne/propriété appelée `name` (`ReaderEntity.java:32`, `ReaderRepository.findByName`). Purement interne (aucun impact observable côté API), mais source de confusion pour la maintenance.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT permettre de créer un lecteur à partir d'un `uid` fourni par le client. Evidence: `api.yaml:395-403`, `ReaderService.java:20-30`.
- **FR-002**: Le système DOIT générer automatiquement un `apitoken` unique à la création si aucun n'est fourni (ce qui est toujours le cas, l'API n'exposant pas ce champ en entrée). Evidence: `ReaderEntity.java:45-50`.
- **FR-003**: État actuel — le système DOIT lister l'intégralité des lecteurs sans pagination, y compris leur `apitoken` en clair. Evidence: `ReaderService.java:32-46`. **Décision (Clarifications 2026-09-24, précisée lors de la spec `007`)** : cible = route ouverte aux utilisateurs connectés de rôle Opérateur ou Administrateur ; `apitoken` n'est inclus dans la réponse que pour les Administrateurs (les Opérateurs reçoivent `uid` et dates uniquement, pour le sélecteur de lignes du tableau de bord et `reader.html`).
- **FR-004**: Le système DOIT accepter l'en-tête `x-api-token` sur `/api/tags/scan` uniquement, en le comparant à `ReaderEntity.apitoken`. Evidence: `security/ReaderApiTokenAuthenticationFilter.java:25,49-59`.
- **FR-005**: État actuel — le système ne DOIT imposer aucune authentification sur la création ni la lecture des lecteurs (`/api/readers`). Evidence: `SecurityConfig.java:32-36`. **Décision (Clarifications 2026-09-24)** : écart à corriger, cohérent avec la décision d'authentification globale de la spec `001` — `POST`/`GET /api/readers` doivent devenir authentifiés.
- **FR-006**: État actuel — le système NE fournit PAS de moyen de mettre à jour, désactiver ou supprimer un lecteur existant. Evidence : absence de ces opérations dans `api.yaml` (section Reader, `api.yaml:20-51`) et dans `ReaderApiDelegate`. **Décision (Clarifications 2026-09-24)** : cible = ajouter une opération de révocation/rotation de jeton et/ou de suppression d'un lecteur.
- **FR-007** (nouveau, Clarifications 2026-09-24) : le système DOIT refuser (`409`) la création d'un lecteur si le `uid` fourni existe déjà. Non implémenté aujourd'hui — evidence de l'état actuel : `ReaderEntity.java:31` (contrainte DB seule, pas de contrôle applicatif).

### Key Entities

- **Reader** (`reader` table) : `id` (UUID), `name`/`uid` (string, unique), `apitoken` (string 64, unique, généré), `creationDate`, `updateDate` (mise à jour automatique `@UpdateTimestamp`, seule entité du domaine à posséder ce champ). Evidence: `ReaderEntity.java`. Cible (spec `003`, Clarifications 2026-09-24) : nouveau champ `mode` (`PRODUCTION` par défaut, ou `ENREGISTREMENT`), modifiable par un Administrateur ; il détermine si les scans du lecteur créent des `Record` ou des lectures temporaires d'enregistrement.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (observé) : une création réussie renvoie toujours un `apitoken` non vide (généré côté serveur, jamais laissé null grâce à `@PrePersist`).
- **SC-002**: [NEEDS CLARIFICATION: **aucun test** ne couvre `ReaderService` ni `ReaderController` (seul `TagServiceTest` existe dans le dépôt). Le comportement en cas de doublon d'`uid` n'est vérifié par aucun test.]
- **SC-003** (résolu, Clarifications 2026-09-24) : aucun jeton de lecteur n'est jamais renvoyé à un appelant non authentifié ni à un Opérateur ; seuls les Administrateurs le reçoivent via `GET /api/readers`.

## Assumptions

- "Lecteur" (doc/front) et "Reader" (code) désignent la même entité.
- Le champ `uid` saisi à la création correspond à un identifiant "métier" lisible (ex. `"Reader 1"`, cf. exemple `api.yaml:401`), pas à un identifiant matériel structuré (MAC, numéro de série) — aucune validation de format n'est appliquée.
- Faute de section dédiée dans `doc/`, ce module est traité comme entièrement "as-is / non spécifié" plutôt que comme une dérive par rapport à un texte de référence.

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| Aucune section "Gestion des lecteurs" dans `doc/20251116-use_cases.md` | Fonctionnalité complète (création + liste) existe en code, API et front, non documentée | `ReaderController.java`, `api.yaml:20-51`, `front/readers.html` |
| `doc/20241103-principle_diagram.puml` : `ClientRFID` appelle l'API pour `requeteConformite()` | Le "ClientRFID" du diagramme correspond en réalité au flux `/tags/scan` (spec `004`), pas à la gestion des lecteurs elle-même ; le diagramme ne distingue pas les deux | `doc/20241103-principle_diagram.puml:14-16`, `TagController.java:29-33` |
| — (aucune section `doc/` dédiée) | `GET /readers` non authentifié, fuite de tous les `apitoken`. Cible retenue (Clarifications 2026-09-24) : route authentifiée | `ReaderController.java:25-29`, `SecurityConfig.java:32-36` |
