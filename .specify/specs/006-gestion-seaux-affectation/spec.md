# Feature Specification: Gestion des seaux et affectation à un cueilleur

**Feature Branch**: `N/A — reverse-engineered from codebase, not developed on a dedicated branch`

**Created**: 2026-09-24

**Status**: As-Is (reverse-engineered) — describes current behavior, not a target design

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

## Clarifications

### Session 2026-09-24

- Q: doc/'s database diagram says a picker has at most one bucket, but the code doesn't enforce it. What should happen when a bucket is assigned to a picker who already has one? → A: Allow several buckets — a picker may have several buckets; the diagram's 0..1 cardinality is wrong and nothing needs enforcing.
- Q: Since a picker can have several buckets, how should the Picker API expose them (it currently has a single bucketNumber and crashes with two buckets)? → A: List of numbers (recommended) — replace bucketNumber with bucketNumbers, a list (empty when none).
- Q: There's no way to remove a picker from a bucket, yet deleting a picker is blocked until all their buckets are unassigned (spec 001). How should unassigning work? → A: DELETE /buckets/{id}/picker (recommended) — a dedicated route that clears the bucket's picker (204, or 404 if the bucket doesn't exist).
- Q: doc/ describes a web page for assigning buckets, but no such page exists in /front. Should that page be built? → A: API-only, external tool — same as spec 003: an external tool calls the API; no /front page to build, and doc/'s UI wording describes that tool. **Superseded** by the next bullet.
- Q (asked during `/speckit-plan` on auth and roles): how should the external tool authenticate? → A: It is not an external tool — bucket assignment is part of the web front, used by a logged-in Administrateur.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consulter les seaux et leurs tags (Priority: P1)

Un client consulte la liste de tous les seaux, avec les tags qui leur sont associés et le cueilleur assigné le cas échéant.

**Evidence**: `controller/BucketController.java:20-23` (`listBuckets`), `service/BucketService.java:35-67`, `api.yaml:271-284`.

**Acceptance Scenarios**:

1. **Given** des seaux existent, **When** `GET /api/buckets` est appelé, **Then** la réponse liste tous les seaux (triés par numéro croissant), chacun avec la liste des UID de tags associés et, si affecté, le cueilleur (nom/prénom/commentaire uniquement — pas son id). Evidence: `BucketService.java:35-67,100-106`.
2. **Given** un `bucketId` précis, **When** `GET /api/buckets/{bucketId}` est appelé, **Then** même structure pour ce seau uniquement, ou `404` si absent. Evidence: `BucketService.java:69-87`.

---

### User Story 2 - Affecter un seau à un cueilleur (Priority: P1)

Un administrateur affecte un seau existant à un cueilleur existant.

**Evidence**: `BucketController.java:30-35` (`assignBucketToPicker`), `BucketService.java:89-98`, `api.yaml:310-335`.

**Acceptance Scenarios**:

1. **Given** le seau et le cueilleur existent tous les deux, **When** `PUT /api/buckets/{bucketId}/picker` est appelé avec `{"pickerId": "..."}`, **Then** le seau est affecté à ce cueilleur (`204`). Evidence: `BucketService.java:89-98`.
2. **Given** le seau ou le cueilleur n'existe pas, **When** la requête est envoyée, **Then** `404` (`BucketNotFoundException` ou `PickerNotFoundException`). Evidence: `BucketService.java:91-94`.
3. **Given** le cueilleur ciblé possède déjà un autre seau, **When** l'affectation est effectuée, **Then** le seau lui est affecté en plus du précédent (`204`). **Résolu (2026-09-24)** : comportement cible — un cueilleur peut avoir plusieurs seaux. Voir Edge Cases pour l'impact sur le module cueilleurs.

### Edge Cases

- **Cardinalité 1 cueilleur ↔ 0..1 seau non appliquée** : `doc/20251013-database_diagram.puml:66` déclare `picker ||--o| bucket`, soit un cueilleur associé à **au plus un** seau. Rien dans le code n'impose cette contrainte : `BucketEntity.picker` (`BucketEntity.java:36-38`) est une simple FK `@ManyToOne`, **sans contrainte d'unicité** sur la colonne `picker_id`, et `BucketService.assignBucketToPicker` (`BucketService.java:89-98`) n'effectue **aucune vérification** qu'un cueilleur n'a pas déjà un seau avant d'en affecter un second. Rien ne désaffecte non plus l'éventuel seau précédent de ce cueilleur. Un même cueilleur peut donc se retrouver, en base, affecté à plusieurs seaux simultanément — ce qui contredit directement `doc/20251013-database_diagram.puml`. **Résolu (2026-09-24)** : le diagramme est obsolète — un cueilleur peut avoir plusieurs seaux, aucune contrainte d'unicité n'est à ajouter.
- **Le module cueilleurs suppose encore un seul seau par cueilleur** (constat, 2026-09-24) : `PickerService.listPickers` construit une map cueilleur → numéro de seau avec `Collectors.toMap` (`PickerService.java:45-47`), qui lève une exception sur clé dupliquée ; `GET /api/pickers` renvoie donc une erreur `500` pour toute la page dès qu'un cueilleur listé a deux seaux. `PickerService.getPicker` utilise `BucketRepository.findByPicker` (`BucketRepository.java:16`, retour `Optional` unique), qui échoue aussi avec deux résultats : `GET /api/pickers/{id}` renvoie `500` pour ce cueilleur. Le schéma `Picker.bucketNumber` (`api.yaml:476-479`) ne porte qu'un seul numéro. Ce bug existe déjà aujourd'hui, puisque la double affectation est possible. Non testé. **Décision (Clarifications 2026-09-24)** : `Picker.bucketNumber` devient `bucketNumbers`, une liste de numéros (vide si aucun seau), sur la liste et le détail des cueilleurs. Aucune page front ne lit ce champ aujourd'hui (`bucketNumber` absent de `front/*.html`), donc seuls l'API et `PickerService` changent.
- **Aucune désaffectation possible** : il n'existe aucune route pour retirer un cueilleur d'un seau (`DELETE /buckets/{id}/picker` absent de `api.yaml`). Seule une réaffectation à un autre cueilleur est possible via le même `PUT`. Combiné à la décision de la spec `001` (suppression d'un cueilleur refusée tant qu'il a un seau), un cueilleur ayant un seau ne peut aujourd'hui jamais être supprimé. **Résolu (2026-09-24)** : ajouter `DELETE /api/buckets/{bucketId}/picker`, qui retire le cueilleur du seau (`204`, ou `404` si le seau n'existe pas). **Livré par la feature `001`** (voir FR-007).
- **Aucune interface utilisateur** : `doc/20251116-use_cases.md:82-100` ("Affecter des seaux") décrit un parcours UI complet (page de gestion des seaux, sélection, liste des cueilleurs, fenêtre de confirmation). Aucune page de `/front` n'appelle `/api/buckets*` (vérifié par recherche de `buckets` dans `front/*.html` — la seule occurrence, `front/index.html:293`, est une variable locale de graphique temporel sans rapport avec l'entité métier "seau"). **Résolu (2026-09-24, révisé lors de `/speckit-plan`)** : il n'y a pas d'outil externe — l'affectation des seaux fait partie du front web, utilisée par un Administrateur connecté. Une page `/front` est à construire, conformément au parcours de `doc/20251116-use_cases.md:82-100`.
- **Aucune authentification** ni sur la lecture ni sur l'affectation, malgré la précondition "L'utilisateur est connecté" de `doc/`. Evidence: `SecurityConfig.java:32-36`. **Décision (réutilisée des specs `001` et `005`)** : écart à corriger — ces routes devront exiger un utilisateur authentifié de rôle Administrateur (l'Opérateur n'a pas accès à la gestion des seaux).
- **Aucun test** pour `BucketService`/`BucketController`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT lister tous les seaux, triés par numéro croissant, avec leurs tags associés et leur cueilleur éventuel. Evidence: `BucketService.java:35-67`.
- **FR-002**: Le système DOIT permettre de consulter le détail d'un seau par id, avec `404` si absent. Evidence: `BucketService.java:69-87`.
- **FR-003**: Le système DOIT permettre d'affecter un seau existant à un cueilleur existant, avec `404` si l'un des deux est absent. Evidence: `BucketService.java:89-98`.
- **FR-004**: Le système NE vérifie PAS qu'un cueilleur n'a pas déjà un seau avant de lui en affecter un nouveau, et NE désaffecte PAS automatiquement un éventuel seau précédent. Evidence: `BucketService.java:89-98`. **Décision (Clarifications 2026-09-24)** : confirmé comme comportement cible — un cueilleur peut avoir plusieurs seaux ; `doc/20251013-database_diagram.puml:66` est obsolète sur ce point.
- **FR-005**: État actuel — le système ne DOIT imposer aucune authentification sur ces routes. Evidence: `SecurityConfig.java:32-36`. **Décision (réutilisée des specs `001` et `005`)** : écart à corriger — rôle Administrateur requis.
- **FR-006** (nouveau, Clarifications 2026-09-24) : l'API cueilleurs DOIT exposer tous les seaux d'un cueilleur sous forme de liste `bucketNumbers` (vide si aucun), et la liste comme le détail des cueilleurs DOIVENT fonctionner quand un cueilleur a plusieurs seaux. Non respecté aujourd'hui (`PickerService.java:45-47,88`, `api.yaml:476-479`).
- **FR-007** (nouveau, Clarifications 2026-09-24) : le système DOIT permettre de retirer le cueilleur d'un seau via `DELETE /api/buckets/{bucketId}/picker` (`204`, ou `404` si le seau n'existe pas). **Livré par la feature `001`** (tâches T001, T021, T022) : `BucketService.unassignBucketFromPicker`, idempotent (`204` aussi si le seau n'avait pas de cueilleur), Administrateur seul.

### Key Entities

- **Bucket** (`bucket` table) : voir spec `003`. Relation `picker` non contrainte en unicité côté base/JPA. Cible (Clarifications 2026-09-24) : relation 1 cueilleur → 0..n seaux, un seau ayant 0..1 cueilleur.
- **Picker** : voir spec `001`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (observé) : une affectation réussie renvoie `204` et le `GET` suivant sur le seau reflète le nouveau cueilleur.
- **SC-002**: [NEEDS CLARIFICATION: aucun test ne vérifie l'absence — ou la présence attendue — d'une contrainte d'unicité cueilleur/seau ; le risque de double affectation n'est couvert par aucun test de régression.]

## Assumptions

- "Seau" (doc/front) et "Bucket" (code) désignent la même entité.
- **Révisé lors de `/speckit-plan`** : ce module est utilisé depuis le front web par un Administrateur connecté ; une page `/front` est prévue.

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| `picker ||--o| bucket` — un cueilleur a au plus un seau (`doc/20251013-database_diagram.puml:66`) | Aucune contrainte d'unicité ; un cueilleur peut être affecté à plusieurs seaux. Confirmé comme cible (Clarifications 2026-09-24) — diagramme BDD à corriger en 1 → 0..n | `BucketEntity.java:36-38`, `BucketService.java:89-98` |
| Parcours UI complet pour l'affectation (`doc/20251116-use_cases.md:82-100`) | Aucune page front n'implémente ce flux ; API seule. Cible (révisée lors de `/speckit-plan`) = page du front web pour un Administrateur connecté | `front/*.html` (absence) |
| Précondition "L'utilisateur est connecté" | Aucune authentification exigée ; cible = rôle Administrateur requis (specs `001`, `005`) | `SecurityConfig.java:32-36` |
