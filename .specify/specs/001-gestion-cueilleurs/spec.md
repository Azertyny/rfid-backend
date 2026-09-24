# Feature Specification: Gestion des cueilleurs (Picker management)

**Feature Branch**: `N/A — reverse-engineered from codebase, not developed on a dedicated branch`

**Created**: 2026-09-24

**Status**: As-Is (reverse-engineered) — describes current behavior, not a target design

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

> This document describes what the system **actually does today**, reconstructed by reading the code. It is not a proposal. Every requirement below cites the file(s) that prove it.

## Clarifications

### Session 2026-09-24

- Q: For the picker module (and by extension the whole API), should adding real authentication/authorization be treated as in-scope going forward, or is "no auth, trusted network" the accepted target state for now? → A: Add auth (recommended) — the missing login/session system is a real gap to close, not the accepted target state.
- Q: When an admin deletes a picker who currently has a bucket assigned, what should the system do? → A: Block with 409 (recommended) — reject the deletion until the bucket is unassigned first, same pattern as `PickerAlreadyExistsException`.
- Q: The API documents a `sort` query parameter for listing pickers, but the code silently ignores it and always sorts by lastname then firstname. What should happen to this parameter? → A: Implement it (recommended) — make `GET /pickers` actually honor `sort` as documented in `api.yaml`.
- Q: Roughly how many pickers should this system be expected to handle, to set a concrete success criterion for the paginated listing? → A: Small (< 200) — fits a single-orchard/small-cooperative harvest operation.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Créer un cueilleur (Priority: P1)

Un administrateur crée une fiche cueilleur (nom, prénom, commentaire optionnel).

**Evidence**: `src/main/java/com/rfidback/controller/PickerController.java:28-30` (`createPicker`), `src/main/java/com/rfidback/service/PickerService.java:71-83`, `front/pickers.html:177-200` (`savePicker`).

**Acceptance Scenarios**:

1. **Given** aucun cueilleur avec le même couple (nom, prénom) n'existe, **When** `POST /api/pickers` est appelé avec `lastname`/`firstname` valides, **Then** le système répond `201` avec la fiche créée (id, dates, etc.). Evidence: `PickerService.java:71-83`, `api.yaml:76-96`.
2. **Given** un cueilleur avec le même couple (nom, prénom) existe déjà (comparaison insensible à la casse), **When** `POST /api/pickers` est rejoué, **Then** le système répond `409` (`PickerAlreadyExistsException`). Evidence: `PickerService.java:117-125`, `exception/PickerAlreadyExistsException.java`.

---

### User Story 2 - Consulter la liste des cueilleurs (Priority: P1)

Un client consulte la liste paginée des cueilleurs, triée par nom puis prénom.

**Evidence**: `PickerController.java:43-50` (`getPickers`), `PickerService.java:38-69`, `front/pickers.html:137-150`.

**Acceptance Scenarios**:

1. **Given** des cueilleurs existent, **When** `GET /api/pickers?page=0&size=10` est appelé, **Then** la réponse contient `content` (fiches, avec `bucketNumber` si un seau leur est affecté — cible : liste `bucketNumbers`, voir FR-004) et `metadata` (page, size, totalElements, totalPages, hasNext, hasPrevious). Evidence: `PickerService.java:38-69`.
2. **Given** un paramètre `sort` différent de `lastname,asc` est fourni, **When** la requête est envoyée, **Then** — comportement actuel — le tri reste **toujours** `lastname ASC, firstname ASC` : le paramètre `sort` est accepté par la signature générée (`PickerApiDelegate.getPickers(..., Optional<String> sort)`) mais **jamais lu** par `PickerController.getPickers` ni transmis à `PickerService.listPickers`. Evidence: `PickerController.java:44-49` (le 3ᵉ argument `sort` n'est jamais utilisé), `PickerService.java:33,39` (tri codé en dur via `DEFAULT_SORT`). **Résolu (2026-09-24)** : cible = implémenter réellement ce paramètre (format `field,asc|desc` déjà documenté en `api.yaml:383-391`) plutôt que de le retirer.

---

### User Story 3 - Mettre à jour un cueilleur (Priority: P2)

Un administrateur modifie nom/prénom/commentaire d'un cueilleur existant.

**Evidence**: `PickerController.java:52-55`, `PickerService.java:93-105`, `front/pickers.html:177-200` (même fonction `savePicker`, avec `id` défini).

**Acceptance Scenarios**:

1. **Given** le cueilleur existe et le nouveau couple (nom, prénom) est unique (en excluant lui-même), **When** `PUT /api/pickers/{id}` est appelé, **Then** la fiche est mise à jour et renvoyée (`200`). Evidence: `PickerService.java:93-105`, `PickerRepository.java:13` (`existsBy...AndIdNot`).
2. **Given** l'id n'existe pas, **When** `PUT /api/pickers/{id}` est appelé, **Then** `404` (`PickerNotFoundException`). Evidence: `PickerService.java:112-115`.

---

### User Story 4 - Supprimer un cueilleur (Priority: P2)

Un administrateur supprime un cueilleur.

**Evidence**: `PickerController.java:32-36`, `PickerService.java:107-110`, `front/pickers.html:201-224` (confirmation puis `DELETE`).

**Acceptance Scenarios**:

1. **Given** le cueilleur existe et n'a **aucun** seau affecté, **When** `DELETE /api/pickers/{id}` est appelé, **Then** `204` et la fiche disparaît. Evidence: `PickerService.java:107-110`.
2. **Given** le cueilleur existe et a un seau affecté (`BucketEntity.picker` pointe vers lui), **When** `DELETE /api/pickers/{id}` est appelé, **Then** l'implémentation actuelle supprime quand même sans vérification (comportement réel) ; le comportement **cible** (Clarifications 2026-09-24) est un rejet `409` tant que le seau n'a pas été désaffecté au préalable — voir Edge Cases.

### Edge Cases

- **Suppression d'un cueilleur ayant un seau affecté** : `doc/20251116-use_cases.md:57` documente un cas alternatif ("le système informe qu'une récolte est potentiellement en cours"). Le code ne contient **aucune** vérification de ce type : `PickerService.deletePicker` (`PickerService.java:107-110`) appelle directement `pickerRepository.delete(entity)` sans consulter `BucketRepository`. `BucketEntity.picker` est une FK `nullable` sans `orphanRemoval` ni `cascade` déclaré (`BucketEntity.java:36-38`), et le comportement exact à l'exécution (mise à `NULL`, erreur de contrainte, ou suppression silencieuse) reste non déterminé faute de test (`src/test` ne contient que `TagServiceTest` et `RfidBackApplicationTests`). **Résolu (2026-09-24)** : la cible est un rejet explicite `409` de la suppression tant qu'un seau reste affecté au cueilleur, plutôt que de laisser le comportement actuel (indéterminé) ou d'implémenter la désaffectation automatique.
- **Authentification absente** : `doc/20251116-use_cases.md` pose comme précondition systématique "L'utilisateur est connecté" pour toutes les actions cueilleur. Il n'existe dans le code **aucune** notion d'utilisateur, de compte, de session ou de login (aucune entité `User`, aucun contrôleur d'authentification). `SecurityConfig` (`configuration/SecurityConfig.java:32-36`) n'exige une authentification que sur `/api/tags/scan` ; toutes les routes `/api/pickers/**` sont `permitAll()`. **Résolu (2026-09-24)** : la précondition "connecté" de `doc/` reflète le comportement cible ; l'absence actuelle d'authentification est un écart à corriger, pas une documentation obsolète (voir Clarifications).
- **Casse et espaces** : la comparaison d'unicité est insensible à la casse et les valeurs sont "trim"ées avant comparaison/écriture (`PickerService.java:145-147`), ce qui n'est précisé nulle part dans `doc/`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT permettre de créer un cueilleur avec `lastname`, `firstname` (obligatoires, ≤50 caractères) et `comment` (optionnel, ≤280 caractères). Evidence: `api.yaml:437-457`, `PickerEntity.java:33-40`.
- **FR-002**: Le système DOIT refuser (409) la création/mise à jour d'un cueilleur si le couple (nom, prénom) existe déjà, comparaison insensible à la casse. Evidence: `PickerService.java:117-125`, `PickerRepository.java:11,13`.
- **FR-003**: État actuel — le système DOIT lister les cueilleurs avec pagination (page/size) et un tri **fixe** nom puis prénom, indépendamment de tout paramètre de tri fourni par l'appelant. Evidence: `PickerService.java:33,38-39`. **Décision (Clarifications 2026-09-24)** : cible = honorer le paramètre `sort` (`field,asc|desc`) quand il est fourni, avec repli sur nom/prénom en son absence.
- **FR-004**: État actuel — le système DOIT exposer, pour chaque cueilleur listé ou consulté, le numéro de seau actuellement affecté s'il existe (un seul, champ `bucketNumber`). Evidence: `PickerService.java:42-56,85-91`. **Décision (spec `006`, Clarifications 2026-09-24)** : un cueilleur peut avoir plusieurs seaux ; le champ devient `bucketNumbers`, une liste (vide si aucun). Aujourd'hui, un cueilleur ayant deux seaux fait échouer la liste et le détail avec une erreur `500` (voir spec `006`, Edge Cases).
- **FR-005**: Le système DOIT permettre la mise à jour d'un cueilleur existant avec la même règle d'unicité que la création (en excluant le cueilleur lui-même). Evidence: `PickerService.java:93-105`.
- **FR-006**: État actuel — le système DOIT permettre la suppression d'un cueilleur par id, **sans** vérification ni avertissement préalable concernant un seau affecté. Evidence: `PickerService.java:107-110`. **Décision (Clarifications 2026-09-24)** : cible = rejeter la suppression avec `409` tant qu'au moins un seau (`BucketEntity.picker`) référence encore ce cueilleur ; l'appelant doit d'abord désaffecter tous ses seaux via `DELETE /api/buckets/{bucketId}/picker` (voir spec `006-gestion-seaux-affectation`).
- **FR-007**: Le système DOIT répondre 404 (`PickerNotFoundException`) pour toute opération de lecture/mise à jour/suppression sur un id inconnu. Evidence: `PickerService.java:112-115`, `exception/PickerNotFoundException.java`.
- **FR-008**: État actuel — le système ne DOIT imposer **aucune** authentification ni autorisation sur les routes `/api/pickers/**`. Evidence: `SecurityConfig.java:32-36` (seul `/api/tags/scan` est `authenticated()`). **Décision (Clarifications 2026-09-24)** : cet état est un écart à corriger, pas la cible ; une future itération DOIT introduire une authentification réelle couvrant `/api/pickers/**` (mécanisme non encore choisi).

### Key Entities

- **Picker** (`picker` table) : `id` (UUID), `lastname`/`firstname` (string 50), `comment` (string 280, nullable), `creationDate` (horodatage de création, non modifiable). Pas de champ `updateDate`, contrairement à `Reader`. Evidence: `PickerEntity.java`.
- **Bucket** (référencé, non détaillé ici) : relation 0..n par cueilleur (spec `006`, Clarifications 2026-09-24), portée par `BucketEntity.picker`, lue en lecture seule depuis ce module. Voir spec `006-gestion-seaux-affectation`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (observé) : une création réussie renvoie `201` avec un corps conforme au schéma `Picker` (`api.yaml:463-482`).
- **SC-002** (observé) : une tentative de doublon (même nom/prénom, casse ignorée) est systématiquement rejetée avec `409`.
- **SC-003** (observé) : `PickerServiceTest` — [NEEDS CLARIFICATION: **aucun test unitaire ou d'intégration n'existe pour `PickerService` ou `PickerController`** ; le seul fichier de test du dépôt (`src/test/java/com/rfidback/service/TagServiceTest.java`) ne couvre pas ce module. Aucune garantie automatisée ne protège ce comportement contre une régression.]
- **SC-004** (résolu, Clarifications 2026-09-24) : le système DOIT rester correct et réactif pour un volume de moins de 200 cueilleurs (échelle "petite exploitation / petite coopérative"), cohérent avec la taille de page par défaut (`size=20`) et l'appel `size=500` déjà effectué en un seul coup par le tableau de bord (`front/index.html:209`).

## Assumptions

- Le champ `sort` de l'API est actuellement inopérant ; il doit être implémenté (Clarifications 2026-09-24) plutôt que retiré de l'API.
- L'absence de toute authentification sur ce module est désormais qualifiée d'écart à corriger (Clarifications 2026-09-24), et non plus d'état cible accepté.
- "Cueilleur" et "Picker" désignent la même entité (terminologie FR côté doc/front, EN côté code).

## Drift vs `doc/`

| Point documenté (`doc/20251116-use_cases.md`) | Comportement réel | Fichiers |
|---|---|---|
| Précondition "L'utilisateur est connecté" pour créer/modifier/supprimer un cueilleur | Aucune authentification n'est exigée sur `/api/pickers/**` | `SecurityConfig.java:32-36` |
| Suppression : avertissement si le cueilleur possède un seau | Suppression inconditionnelle, sans vérification du seau associé. Cible retenue (Clarifications 2026-09-24) : rejet `409` plutôt qu'un simple avertissement | `PickerService.java:107-110` |
| — (non mentionné) | Le tri (`sort`) exposé par l'API est ignoré ; tri fixe nom/prénom. Cible retenue (Clarifications 2026-09-24) : l'implémenter | `PickerController.java:44-49`, `PickerService.java:33` |
