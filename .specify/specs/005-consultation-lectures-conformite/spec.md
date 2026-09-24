# Feature Specification: Consultation des lectures et bascule de conformité

**Feature Branch**: `N/A — reverse-engineered from codebase, not developed on a dedicated branch`

**Created**: 2026-09-24

**Status**: As-Is (reverse-engineered) — describes current behavior, not a target design

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

## Clarifications

### Session 2026-09-24

- Q: doc/ assigns the compliance-review use case to an "Opérateur" actor, but only "Administrateur" is defined and the code has no roles. Is the Opérateur a distinct role from the Administrateur? → A: Distinct, restricted role (recommended) — production-line staff who can only view the last reads and toggle compliance; the future auth system needs at least 2 roles.
- Q: reader.html sends the reader's x-api-token on both /records calls, but the backend never checks it. How should these two routes be protected? → A: Opérateur user login (recommended) — both routes require an authenticated user with role Opérateur or Administrateur; reader tokens stay machine-only.
- Q: When an Opérateur overrides a record's compliance, nothing records who changed it, when, or the reader's original verdict. Should overrides be traced? → A: Keep who/when + original (recommended) — store the reader's original verdict plus the author and time of the override.
- Q: Should compliance overrides be stored as extra columns on the record table, or in a separate table? → A: Separate table (recommended) — a dedicated history table keeps every change, not just the latest, so disputes with pickers can be resolved.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consulter les 10 dernières lectures d'un lecteur (Priority: P1)

Un opérateur, sur l'écran dédié à un lecteur RFID, voit les 10 derniers tags scannés par ce lecteur.

**Evidence**: `controller/RecordController.java:19-22` (`listLatestRecordsForReader`), `service/RecordService.java:28-42`, `front/reader.html:190-217` (`fetchRecords`, `renderBoxes`).

**Acceptance Scenarios**:

1. **Given** un lecteur existant identifié par son `uid` (paramètre de chemin nommé `readerId` mais résolu par nom/uid, pas par UUID technique), **When** `GET /api/records/readers/{readerId}` est appelé, **Then** le système renvoie au plus les 10 `Record` les plus récents de ce lecteur, triés du plus récent au plus ancien. Evidence: `RecordService.java:30,33`, `repository/RecordRepository.java:11` (`findTop10ByReader_NameOrderByCreationDateDesc`).
2. **Given** le `uid` ne correspond à aucun lecteur, **When** la requête est envoyée, **Then** `404` (`ReaderNotFoundException`). Evidence: `RecordService.java:30-31`.

---

### User Story 2 - Basculer la conformité d'une lecture (Priority: P1)

Un opérateur clique sur un tag affiché à l'écran pour en inverser la conformité.

**Evidence**: `RecordController.java:24-28` (`updateRecordConformity`), `RecordService.java:60-66`, `front/reader.html:249-273` (`toggleCompliance`).

**Acceptance Scenarios**:

1. **Given** un `recordId` existant, **When** `PATCH /api/records/{recordId}/conformity` est appelé avec `{"isCompliant": <bool>}`, **Then** le `Record` est mis à jour avec cette valeur exacte et `204` est renvoyé. Evidence: `RecordService.java:60-66`.
2. **Given** l'inversion perçue côté utilisateur ("clique pour inverser"), **When** on regarde qui calcule la valeur inverse, **Then** ce n'est **pas** le serveur : l'API exige la valeur finale explicite (`isCompliant` obligatoire, `api.yaml:636-644`) ; c'est `front/reader.html` (fonction `toggleCompliance`) qui lit la valeur actuelle affichée et envoie son inverse. Le backend n'offre aucune opération "toggle" atomique. Evidence: `api.yaml:242-266` (pas de paramètre optionnel, `isCompliant` requis), `front/reader.html:249-273`.
3. **Given** le `recordId` n'existe pas, **When** la requête est envoyée, **Then** `404` (`RecordNotFoundException`). Evidence: `RecordService.java:62-63`.

### Edge Cases

- **Incohérence des acteurs documentés** : `doc/20251116-use_cases.md:108` attribue ce cas d'usage à l'acteur "Opérateur", mais la section `## Acteurs` (`doc/20251116-use_cases.md:1-3`) ne définit **que** "Administrateur". "Opérateur" est utilisé sans jamais être déclaré. Le code, de son côté, ne connaît aucun rôle ni acteur différencié : ni "Administrateur" ni "Opérateur" n'existent comme concept technique. **Résolu (2026-09-24)** : rôle distinct — l'Opérateur est un rôle restreint (consultation des dernières lectures et bascule de conformité uniquement), distinct de l'Administrateur. La section "Acteurs" de `doc/` est incomplète sur ce point.
- **Jeton envoyé mais non vérifié** : `front/reader.html:196-199,259-262` joint systématiquement l'en-tête `x-api-token` du lecteur sélectionné à ces deux appels. Or `SecurityConfig.java:32-36` ne protège que `/api/tags/scan` — ces deux routes (`/records/readers/{id}`, `/records/{id}/conformity`) sont `permitAll()` et n'exploitent jamais ce jeton. Le comportement de sécurité perçu côté front (poste "authentifié") ne correspond à aucune vérification serveur réelle. **Résolu (2026-09-24)** : ces deux routes devront exiger une connexion utilisateur (rôle Opérateur ou Administrateur), pas le jeton du lecteur. L'en-tête `x-api-token` envoyé par `reader.html` n'a pas vocation à protéger ces routes.
- **Absence de traçabilité de la modification manuelle** : la bascule de conformité écrase silencieusement le `compliant` initial du scan (voir spec `004`), sans conserver de trace de qui/quand a modifié la valeur (`comment` de `RecordEntity` n'est jamais renseigné par ce flux). Pas d'audit. **Décision (Clarifications 2026-09-24)** : cible = enregistrer chaque modification manuelle dans une table d'historique dédiée (ancienne valeur, nouvelle valeur, auteur, date), sans perdre les modifications intermédiaires. Le verdict initial du lecteur est l'ancienne valeur de la première modification. La valeur de l'Opérateur reste celle qui fait foi (spec `004`).
- **Aucun test** : `RecordService`/`RecordController` ne sont couverts par aucun test automatisé.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT renvoyer les 10 derniers `Record` d'un lecteur donné par son `uid`, triés par date décroissante. Evidence: `RecordRepository.java:11`.
- **FR-002**: Le système DOIT renvoyer `404` si le `uid` de lecteur est inconnu. Evidence: `RecordService.java:30-31`.
- **FR-003**: Le système DOIT permettre de fixer explicitement la conformité d'un `Record` existant via une valeur booléenne fournie par le client (pas d'inversion serveur). Evidence: `RecordService.java:60-66`. **Décision (Clarifications 2026-09-24)** : chaque modification DOIT créer une entrée dans l'historique des modifications de conformité (ancienne valeur, nouvelle valeur, auteur, date) ; `Record.compliant` porte la valeur courante, qui fait foi (spec `004`).
- **FR-004**: État actuel — le système ne DOIT imposer aucune authentification sur ces deux routes, malgré la présence d'un en-tête `x-api-token` envoyé par le front. Evidence: `SecurityConfig.java:32-36`. **Décision (Clarifications 2026-09-24)** : écart à corriger — ces routes DOIVENT exiger un utilisateur authentifié de rôle Opérateur ou Administrateur (cohérent avec la décision d'authentification globale de la spec `001`).
- **FR-005** (nouveau, Clarifications 2026-09-24) : le système DOIT distinguer au moins deux rôles : Administrateur (toutes les fonctions) et Opérateur (consultation des lectures et bascule de conformité uniquement). Non implémenté aujourd'hui.

### Key Entities

- **Record** (déjà décrit en spec `004`) : consommé ici en lecture (les 10 derniers d'un lecteur) et modifié en écriture (`compliant` uniquement). Cible (Clarifications 2026-09-24) : `compliant` reste la valeur courante ; l'historique des modifications est porté par une entité séparée (ci-dessous).
- **Modification de conformité** (nouvelle entité cible, Clarifications 2026-09-24, non implémentée) : une ligne par modification manuelle — `record` (FK vers `Record`), ancienne valeur, nouvelle valeur, auteur (utilisateur authentifié), date. Relation : un `Record` a 0..n modifications. Le verdict initial du lecteur se lit dans l'ancienne valeur de la première modification (ou dans `compliant` s'il n'y en a aucune).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (observé, non testé automatiquement) : la liste renvoyée ne dépasse jamais 10 éléments, conformément à `doc/20251116-use_cases.md:111` ("Les 10 derniers TAGs lus par lecteur sont affichés"). Evidence: `RecordRepository.java:11` (`findTop10By...`), mais [NEEDS CLARIFICATION: aucun test ne vérifie cette limite].
- **SC-002**: [NEEDS CLARIFICATION: aucun test unitaire ou d'intégration n'existe pour ce module.]
- **SC-003**: [NEEDS CLARIFICATION: aucune cible de latence n'est documentée pour l'affichage temps réel en ligne de production, bien que `front/config.js` définisse un `POLLING_INTERVAL` de 500 ms côté client — aucune contrepartie de charge/latence n'est spécifiée côté serveur.]

## Assumptions

- **Confirmé (Clarifications 2026-09-24)** : "Opérateur" et "Administrateur" sont deux rôles distincts ; l'Opérateur a des droits restreints.
- Le paramètre de chemin `readerId` correspond en réalité à l'`uid`/`name` du lecteur, pas à son identifiant technique UUID (`ReaderEntity.id`) — confirmé par `RecordService.java:30` (`readerRepository.findByName`).

## Drift vs `doc/`

| Point documenté (`doc/20251116-use_cases.md:102-115`) | Comportement réel | Fichiers |
|---|---|---|
| Acteur "Opérateur" | Non défini dans la liste des acteurs (`## Acteurs`) ; aucun rôle distinct dans le code ; cible (Clarifications 2026-09-24) = rôle distinct et restreint, à ajouter à la section Acteurs de `doc/` | `doc/20251116-use_cases.md:1-3,108` |
| "Le système change le champ de conformité à l'inverse de la valeur précédente" | L'inversion est calculée côté front (`reader.html`), le backend se contente d'appliquer la valeur reçue | `RecordService.java:60-66`, `front/reader.html:249-273` |
| (implicite) action nécessitant d'être un opérateur identifié | Aucune authentification/autorisation réelle sur ces routes ; cible (Clarifications 2026-09-24) = connexion utilisateur, rôle Opérateur ou Administrateur | `SecurityConfig.java:32-36` |
