# Feature Specification: Gestion des lecteurs RFID (Reader registration)

**Feature Branch**: `feature/002-gestion-lecteurs`

**Created**: 2026-09-24

**Status**: Implémenté (2026-09-24) — la spec décrit l'état initial (as-is) puis la cible, livrée sur `feature/002-gestion-lecteurs`

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

> [NEEDS CLARIFICATION] Ce module n'a **aucune** section dédiée dans `doc/20251116-use_cases.md` (seul `doc/20241103-principle_diagram.puml` mentionne un acteur `ClientRFID` de façon très générale). Toute la spécification ci-dessous est donc reconstruite uniquement à partir du code et de l'API, sans texte métier de référence à comparer.

## Clarifications

### Session 2026-09-24

- Q: GET /api/readers is unauthenticated and returns every reader's API token in plain text — the same tokens that protect /tags/scan. How should reader tokens be handled? → A: Keep in list, behind auth — GET /readers keeps returning apitoken, but the route requires authentication (same auth system decided as target in spec 001). *(Refined by the spec 007 answer below: only Administrateurs receive `apitoken`.)*
- Q: There's no route to update, revoke, or delete a reader once created. Should the target design add a way to revoke/rotate a reader's token? → A: Add revoke/rotate (recommended) — add an endpoint to regenerate a reader's token and/or delete a reader. *(Superseded by the FR-006 answer below: rotation plus deactivation, no deletion.)*
- Q: Creating a reader with a uid that's already taken currently falls through to a raw database constraint violation instead of an explicit 409. Should reader creation get the same explicit duplicate-check treatment as picker creation? → A: Yes, return 409 (recommended) — add an explicit uniqueness check before insert, mirroring PickerService.ensureUniqueName.
- Q (asked during spec 007): Opérateurs need the reader list (dashboard, reader.html), but GET /readers was planned Administrateur-only because it returns API tokens. How should Opérateurs get the list? → A: Hide tokens from Opérateurs — GET /readers is open to Opérateur and Administrateur; apitoken is only included for Administrateurs.
- Q: Which lifecycle operations should a reader get: token rotation only, deletion, or deactivation? (FR-006) → A: Token rotation plus deactivation — rotation replaces the token (the old one stops working immediately); a deactivated reader's token is refused on /tags/scan, and the reader stays listed with its records. No hard delete.
- Q: Can an Administrateur reactivate a deactivated reader, or is deactivation permanent? → A: Reversible — an Administrateur can reactivate the reader and its current token works again.
- Q: Which `uid` values should reader creation accept, and when do two `uid`s count as duplicates? (FR-001, FR-007) → A: Same rule as pickers — trim, reject blank with 400, max 50 characters, duplicate if equal ignoring case → 409.
- Q: Should `GET /api/readers` return deactivated readers to everyone, or only to Administrateurs? → A: Everyone gets all readers, each with an `active` flag; the front decides what to display.
- Q: Should the reader's production/registration mode be delivered with reader management (002), or stay with tag registration (003) as planned? → A: Keep it in 003 — the `mode` field, its switch in `readers.html`, scan routing and temporary reads ship together; 002 only leaves room for `mode` in `UpdateReader`.

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

---

### User Story 3 - Désactiver / réactiver un lecteur (Priority: P2)

Cible (Clarifications 2026-09-24, FR-006) — non implémenté aujourd'hui. Un Administrateur désactive un lecteur perdu, volé ou retiré du service, pour que son jeton ne soit plus accepté, sans perdre l'historique de ses lectures. Il peut le réactiver plus tard.

**Why this priority**: c'est le seul moyen de couper un appareil sans le supprimer ; les `Record` exigent leur lecteur (`RecordEntity.java:44-46`).

**Acceptance Scenarios**:

1. **Given** un lecteur actif, **When** un Administrateur le désactive, **Then** la réponse est `200` avec `active: false`, et un scan `POST /api/tags/scan` avec son jeton reçoit `401`.
2. **Given** un lecteur désactivé, **When** un Administrateur le réactive, **Then** la réponse est `200` avec `active: true`, et un scan avec le même jeton reçoit de nouveau `200`.
3. **Given** un lecteur désactivé, **When** un Opérateur ou un Administrateur appelle `GET /api/readers`, **Then** le lecteur figure dans la liste avec `active: false`, et ses `Record` existants restent consultables.
4. **Given** un Opérateur connecté, **When** il tente de désactiver un lecteur, **Then** `403`.
5. **Given** un identifiant de lecteur inconnu, **When** un Administrateur tente de le désactiver, **Then** `404`.

---

### User Story 4 - Régénérer le jeton d'un lecteur (Priority: P2)

Cible (Clarifications 2026-09-24, FR-006) — non implémenté aujourd'hui. Un Administrateur remplace le jeton d'un lecteur dont le jeton a fuité (ex. l'ancien jeton codé en dur dans `front/index.html`), puis reconfigure l'appareil avec le nouveau.

**Why this priority**: un jeton compromis ne peut aujourd'hui ni être révoqué ni être remplacé (voir Edge Cases).

**Acceptance Scenarios**:

1. **Given** un lecteur dont le jeton est T1, **When** un Administrateur régénère son jeton, **Then** la réponse est `200` avec un nouveau jeton T2 ≠ T1 (32 caractères hexadécimaux).
2. **Given** cette régénération, **When** un scan est envoyé avec T1, **Then** `401` ; avec T2, **Then** `200`.
3. **Given** un lecteur désactivé, **When** un Administrateur régénère son jeton, **Then** `200`, et le lecteur reste désactivé.
4. **Given** un Opérateur connecté, **When** il tente de régénérer un jeton, **Then** `403`.

### Edge Cases

- **Fuite de jetons d'API** : `GET /api/readers` n'exige aucune authentification (`SecurityConfig.java:32-36` : seule `/api/tags/scan` est protégée). Or c'est précisément l'`apitoken` renvoyé par cette route qui sert de secret pour s'authentifier sur `/api/tags/scan` (`security/ReaderApiTokenAuthenticationFilter.java:49-59`). N'importe quel appelant non authentifié peut donc lister et récupérer les jetons de tous les lecteurs. `front/index.html:155` va plus loin et **code en dur** un jeton (`API_TOKEN = "176c77ca6757494f9729784263c6022d"`) dans une page HTML statique servie publiquement (`deploy/front`, `Dockerfile`). **Résolu (2026-09-24)** : faille à corriger — `GET /readers` doit devenir authentifié (Opérateur ou Administrateur), et les jetons ne doivent être renvoyés qu'aux Administrateurs. Le jeton codé en dur dans `front/index.html:155` doit être retiré et considéré comme compromis : il sera le premier à régénérer une fois la rotation disponible (voir spec `007`, Clarifications 2026-09-24). **Livré** : spec `008` a protégé `GET /readers` et retiré le jeton codé en dur de `front/index.html` ; la rotation (FR-006) permet désormais de régénérer ce jeton compromis.
- **Pas de mise à jour ni de suppression** : aucune route `PUT`/`DELETE` pour `/readers` n'existe dans `api.yaml` ni dans `ReaderApiDelegate` généré. Un jeton compromis ne peut donc pas être révoqué ni régénéré via l'API. **Résolu (2026-09-24)** : manquant — la cible ajoute la rotation du jeton et la désactivation d'un lecteur (voir FR-006). Pas de suppression physique : chaque `Record` référence obligatoirement son lecteur (`RecordEntity.java:44-46`, `reader_id` non nul), une suppression casserait l'historique de conformité.
- **Incohérence de nommage `uid` / `name`** : l'API et le diagramme de base de données (`doc/20251013-database_diagram.puml:57`) nomment ce champ `uid`, mais l'entité JPA le stocke dans une colonne/propriété appelée `name` (`ReaderEntity.java:32`, `ReaderRepository.findByName`). Purement interne (aucun impact observable côté API), mais source de confusion pour la maintenance.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT permettre de créer un lecteur à partir d'un `uid` fourni par le client. Evidence: `api.yaml:395-403`, `ReaderService.java:20-30`.
- **FR-001a** (nouveau, Clarifications 2026-09-24) : un `uid` de plus de 50 caractères DOIT être refusé (`400`), la longueur étant comptée sur la valeur reçue, avant suppression des espaces (même règle que les cueilleurs, `CreatePicker.lastname`). Le `uid` DOIT ensuite être débarrassé des espaces en début/fin avant enregistrement, et refusé (`400`) s'il est vide après cette opération. État actuel : seule la présence du champ est exigée (`api.yaml`, `CreateReader.required`), `""` et `"   "` sont acceptés. **Livré** : `api.yaml` (`maxLength: 50`) et `ReaderService.createReader`.
- **FR-002**: Le système DOIT générer automatiquement un `apitoken` unique à la création si aucun n'est fourni (ce qui est toujours le cas, l'API n'exposant pas ce champ en entrée). Evidence: `ReaderEntity.java:45-50`.
- **FR-003**: État actuel — le système DOIT lister l'intégralité des lecteurs sans pagination, y compris leur `apitoken` en clair. Evidence: `ReaderService.java:32-46`. **Décision (Clarifications 2026-09-24, précisée lors de la spec `007`)** : cible = route ouverte aux utilisateurs connectés de rôle Opérateur ou Administrateur ; `apitoken` n'est inclus dans la réponse que pour les Administrateurs (les Opérateurs reçoivent `uid`, `active` et dates uniquement, pour le sélecteur de lignes du tableau de bord et `reader.html`). La liste contient tous les lecteurs, actifs comme désactivés, quel que soit le rôle ; chaque élément porte un indicateur `active`, et c'est au front de décider de l'affichage des lecteurs désactivés (Clarifications 2026-09-24). **Livré (spec `008`)** pour l'authentification et le masquage d'`apitoken` ; **livré (spec `002`)** pour `id` et `active` dans la réponse (`ReaderService.toModel`).
- **FR-004**: Le système DOIT accepter l'en-tête `x-api-token` sur `/api/tags/scan` uniquement, en le comparant à `ReaderEntity.apitoken`. Evidence: `security/ReaderApiTokenAuthenticationFilter.java:25,49-59`.
- **FR-005**: État actuel — le système ne DOIT imposer aucune authentification sur la création ni la lecture des lecteurs (`/api/readers`). Evidence: `SecurityConfig.java:32-36`. **Décision (Clarifications 2026-09-24)** : écart à corriger, cohérent avec la décision d'authentification globale de la spec `001` — `POST`/`GET /api/readers` doivent devenir authentifiés. **Livré (spec `008`)** : matrice d'accès de `SecurityConfig`.
- **FR-006**: État actuel — le système NE fournit PAS de moyen de mettre à jour, désactiver ou supprimer un lecteur existant. Evidence : absence de ces opérations dans `api.yaml` (section Reader, `api.yaml:20-51`) et dans `ReaderApiDelegate`. **Décision (Clarifications 2026-09-24)** : cible = deux opérations réservées aux Administrateurs :
  - **Rotation du jeton** : génère un nouvel `apitoken` pour le lecteur et le renvoie ; l'ancien jeton DOIT être refusé sur `/api/tags/scan` (`401`) dès la fin de l'opération.
  - **Désactivation** : un lecteur désactivé DOIT voir son jeton refusé sur `/api/tags/scan` (`401`) ; il reste présent dans `GET /api/readers` pour tous les rôles (indicateur `active`, voir FR-003) et ses `Record` existants sont conservés. La désactivation est réversible : un Administrateur peut réactiver le lecteur, dont le jeton courant redevient alors valide (une rotation reste possible si le jeton est suspect).
  - Aucune suppression physique d'un lecteur n'est prévue. **Livré** : `PATCH /api/readers/{readerId}` et `POST /api/readers/{readerId}/token` ; refus du jeton dans `ReaderApiTokenAuthenticationFilter`.
- **FR-007** (nouveau, Clarifications 2026-09-24) : le système DOIT refuser (`409`) la création d'un lecteur si le `uid` fourni existe déjà, la comparaison se faisant après suppression des espaces en début/fin et sans tenir compte de la casse (`"Reader 1"` et `"reader 1 "` sont des doublons), comme `PickerService.ensureUniqueName`. Limite acceptée : deux créations simultanées de `uid` ne différant que par la casse peuvent toutes deux aboutir (la contrainte d'unicité en base est sensible à la casse) ; deux créations simultanées du même `uid` exact DOIVENT aussi répondre `409` (violation de la contrainte en base traduite en `409`, pas `500`). Non implémenté aujourd'hui — evidence de l'état actuel : `ReaderEntity.java:31` (contrainte DB seule, pas de contrôle applicatif). **Livré** : `ReaderService.createReader` (`existsByNameIgnoreCase`, puis `DataIntegrityViolationException` → `ReaderAlreadyExistsException`).

### Key Entities

- **Reader** (`reader` table) : `id` (UUID), `name`/`uid` (string, unique), `apitoken` (string 64, unique, généré), `creationDate`, `updateDate` (mise à jour automatique `@UpdateTimestamp`, seule entité du domaine à posséder ce champ). Evidence: `ReaderEntity.java`. Cible (spec `003`, Clarifications 2026-09-24 ; hors périmètre de `002`, voir Assumptions) : nouveau champ `mode` (`PRODUCTION` par défaut, ou `ENREGISTREMENT`), modifiable par un Administrateur ; il détermine si les scans du lecteur créent des `Record` ou des lectures temporaires d'enregistrement. Cible (Clarifications 2026-09-24, FR-006) : nouvel état actif/désactivé (actif par défaut, réversible par un Administrateur) ; un lecteur désactivé ne peut plus s'authentifier.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (observé) : une création réussie renvoie toujours un `apitoken` non vide (généré côté serveur, jamais laissé null grâce à `@PrePersist`).
- **SC-002** (livré) : `ReaderServiceTest`, `ReaderApiTest`, `ReaderScanSecurityTest`, `ReaderTokenVisibilityTest` et `AccessMatrixSecurityTest` couvrent la création (dont le doublon d'`uid`), la liste, la désactivation et la rotation.
- **SC-003** (résolu, Clarifications 2026-09-24) : aucun jeton de lecteur n'est jamais renvoyé à un appelant non authentifié ni à un Opérateur ; seuls les Administrateurs le reçoivent via `GET /api/readers`.

## Assumptions

- "Lecteur" (doc/front) et "Reader" (code) désignent la même entité.
- Le champ `uid` saisi à la création correspond à un identifiant "métier" lisible (ex. `"Reader 1"`, cf. exemple `api.yaml:401`), pas à un identifiant matériel structuré (MAC, numéro de série) — pas de validation de format au-delà de la règle de FR-001a (non vide, 50 caractères max).
- Faute de section dédiée dans `doc/`, ce module est traité comme entièrement "as-is / non spécifié" plutôt que comme une dérive par rapport à un texte de référence.
- Hors périmètre (Clarifications 2026-09-24) : le mode du lecteur (`PRODUCTION` / `ENREGISTREMENT`) est livré par la spec `003` (FR-007), avec l'orientation des scans et les lectures temporaires qu'il pilote. Un mode sans cette orientation laisserait un lecteur "enregistrement" créer des `Record` de conformité. La spec `002` se contente de prévoir le champ dans `UpdateReader` (`PATCH /api/readers/{readerId}`).

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| Aucune section "Gestion des lecteurs" dans `doc/20251116-use_cases.md` | Fonctionnalité complète (création + liste) existe en code, API et front, non documentée | `ReaderController.java`, `api.yaml:20-51`, `front/readers.html` |
| `doc/20241103-principle_diagram.puml` : `ClientRFID` appelle l'API pour `requeteConformite()` | Le "ClientRFID" du diagramme correspond en réalité au flux `/tags/scan` (spec `004`), pas à la gestion des lecteurs elle-même ; le diagramme ne distingue pas les deux | `doc/20241103-principle_diagram.puml:14-16`, `TagController.java:29-33` |
| — (aucune section `doc/` dédiée) | `GET /readers` non authentifié, fuite de tous les `apitoken`. Cible retenue (Clarifications 2026-09-24) : route authentifiée — **livré** par spec `008` | `ReaderController.java:25-29`, `SecurityConfig.java:32-36` |
