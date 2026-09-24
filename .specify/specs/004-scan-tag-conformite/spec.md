# Feature Specification: Scan d'un TAG par un lecteur (déclaration de conformité)

**Feature Branch**: `N/A — reverse-engineered from codebase, not developed on a dedicated branch`

**Created**: 2026-09-24

**Status**: As-Is (reverse-engineered) — describes current behavior, not a target design

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

## Clarifications

### Session 2026-09-24

- Q: doc/'s tag lifecycle diagram describes 3 states (IDLE → EnAttente → Comptabilisé) with controlled transitions, but the code has no state field anywhere and a scan just creates the tag and record in one step. Should this state machine actually be built, or is the diagram stale documentation that should be dropped? → A: Drop the diagram (recommended) — treated as obsolete/aspirational; the simpler "scan creates tag+record directly" model is the target design. No state machine to build.
- Q: Compliance on a Record can currently be set two different ways with no arbitration rule: the reader hardware reports isCompliant at scan time, and an operator can later overwrite it via PATCH /records/{id}/conformity. Which should be the authoritative source going forward? → A: Human review is authoritative (recommended) — the reader's scan-time value is an initial/default flag; the operator's manual review is the final word and may override it at any time.
- Q: doc/'s database diagram documents conformity as a 3-value ENUM('OK','NOK','CHEAT'), but the code only has a boolean — the CHEAT state doesn't exist anywhere. Should CHEAT be dropped too (consistent with dropping the lifecycle diagram), or is it still a real state to add? → A: Drop CHEAT, keep boolean (recommended) — the 3-value ENUM in the DB diagram is stale; boolean compliant/non-compliant is the confirmed target model.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un lecteur RFID signale la lecture d'un tag (Priority: P1)

Un lecteur RFID physique, authentifié par son jeton d'API, envoie l'UID d'un tag scanné et son propre verdict de conformité ; le système enregistre un `Record`.

**Evidence**: `controller/TagController.java:28-33` (`scanTag`), `service/TagService.java:36-62`, `security/ReaderApiTokenAuthenticationFilter.java`, `api.yaml:188-214`.

**Acceptance Scenarios**:

1. **Given** un en-tête `x-api-token` valide (correspondant à un `ReaderEntity.apitoken`), **When** `POST /api/tags/scan` est appelé avec `{"uid": "...", "isCompliant": true|false}`, **Then** le système authentifie la requête (`ReaderAuthentication`), résout le lecteur depuis le contexte de sécurité, et crée un `RecordEntity` lié au tag, au lecteur et — si le tag est déjà associé à un seau lui-même affecté à un cueilleur — au cueilleur. Evidence: `TagController.java:28-33`, `TagService.java:36-62`.
2. **Given** l'en-tête `x-api-token` est absent ou invalide, **When** `POST /api/tags/scan` est appelé, **Then** `401` avant même d'atteindre le contrôleur (filtre de sécurité). Evidence: `ReaderApiTokenAuthenticationFilter.java:49-59`.
3. **Given** l'UID scanné n'existe pas encore en base, **When** le scan est traité, **Then** un nouveau `TagEntity` est créé à la volée, **sans** seau associé. Evidence: `TagService.java:42-43`.
4. **Given** le tag scanné est associé à un seau lui-même affecté à un cueilleur, **When** le scan est traité, **Then** le `Record` créé porte le `pickerId` de ce cueilleur ; sinon `pickerId` est `null`. Evidence: `TagService.java:46,50`, test `TagServiceTest.java:76-97`.

### Edge Cases

- **Aucune machine à états** : `doc/20241103-tags_lifecycle_diagram.puml` documente un cycle de vie du Tag en 3 états (`IDLE` → `EnAttente` → `Comptabilisé`), avec des transitions explicites ("Enregistrement", "Affectation", "Démarrage de l'activité liée", "Lecture pendant l'activité"). **Aucun champ d'état** n'existe sur `TagEntity` (`entity/TagEntity.java`) ni sur aucune autre entité du domaine, et aucune règle du code ne refuse un scan sous prétexte qu'un tag ne serait pas "en attente" d'une activité. Un scan crée le tag et le `Record` en une seule étape, sans jamais vérifier d'état préalable. Evidence: absence de champ `status`/`state` dans `TagEntity.java`, `BucketEntity.java`, `RecordEntity.java`. **Résolu (2026-09-24)** : obsolète — le diagramme de cycle de vie est abandonné ; le modèle cible reste "un scan crée directement le tag et le `Record`", sans machine à états à construire.
- **Double source de vérité pour la conformité** : ce endpoint laisse le **lecteur matériel** déclarer lui-même `isCompliant` (`ScanTagRequest.isCompliant`, `api.yaml:526-539`) au moment du scan. Le module `005-consultation-lectures-conformite` permet ensuite à un opérateur humain de **modifier** cette même valeur a posteriori (`PATCH /records/{id}/conformity`). Le code ne documente ni ne garantit laquelle des deux sources fait foi en cas de désaccord — la dernière écriture gagne simplement. **Résolu (2026-09-24)** : la relecture humaine fait foi — la valeur `isCompliant` du scan matériel n'est qu'un indicateur initial ; la bascule manuelle par un opérateur (`PATCH /records/{id}/conformity`, voir spec `005`) est la valeur finale et peut remplacer celle du lecteur à tout moment. Ce remplacement est réservé aux rôles Opérateur et Administrateur, et chaque modification est enregistrée dans une table d'historique dédiée, ce qui conserve le verdict initial du lecteur (spec `005`, Clarifications 2026-09-24).
- **Enum de conformité non implémenté** : `doc/20251013-database_diagram.puml:49` documente `conformity : ENUM('OK','NOK','CHEAT')` — trois valeurs possibles. `RecordEntity.compliant` (`entity/RecordEntity.java:48-49`) est un simple `boolean` : seules deux valeurs sont représentables, l'état `CHEAT` documenté n'existe nulle part dans le code. **Résolu (2026-09-24)** : abandonné — cohérent avec l'abandon du diagramme de cycle de vie, `CHEAT` n'est pas un état à ajouter ; le booléen `compliant` reste le modèle cible.
- **Filtre de sécurité par chemin exact codé en dur** : `ReaderApiTokenAuthenticationFilter` ne protège que la chaîne exacte `/api/tags/scan` après retrait du context-path (`ReaderApiTokenAuthenticationFilter.java:29-36`). Toute évolution du chemin (context-path applicatif, réécriture par un reverse proxy, nouvelle route) contournerait silencieusement la protection. Non testé.
- **Aucun test de la couche sécurité/contrôleur** : `TagServiceTest` teste `TagService.registerScan` directement (avec un `ReaderEntity` fourni à la main), mais aucun test n'exerce `ReaderApiTokenAuthenticationFilter` ni `TagController` avec une vraie requête HTTP.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT exiger un en-tête `x-api-token` valide pour `POST /api/tags/scan`, et renvoyer `401` sinon. Evidence: `ReaderApiTokenAuthenticationFilter.java:49-59`, `SecurityConfig.java:34`.
- **FR-002**: Le système DOIT créer le tag scanné s'il est inconnu, sans validation de format d'UID au-delà de "non vide". Evidence: `TagService.java:40-43`.
- **FR-003**: Le système DOIT créer un `Record` pour chaque scan, portant le tag, le lecteur authentifié, et le cueilleur déduit du seau du tag (le cas échéant). Evidence: `TagService.java:44-54`. **Décision (spec `003`, Clarifications 2026-09-24)** : uniquement pour un lecteur en mode `PRODUCTION` ; un lecteur en mode `ENREGISTREMENT` utilise le même endpoint, mais ses scans deviennent des lectures temporaires d'enregistrement, sans `Record`. Le lecteur lui-même n'a rien à changer.
- **FR-004**: Le système DOIT accepter la valeur `isCompliant` fournie par le lecteur telle quelle, sans recalcul serveur. Evidence: `TagService.java:45,51`.
- **FR-005**: Le système DOIT horodater le `Record` à la persistance (`creationDate`, non modifiable). Evidence: `RecordEntity.java:51-53`.
- **FR-006**: Le système NE distingue PAS d'état "CHEAT" ; `compliant` est binaire. Evidence: `RecordEntity.java:48-49`. **Décision (Clarifications 2026-09-24)** : confirmé comme modèle cible — `doc/20251013-database_diagram.puml:49` est obsolète sur ce point.
- **FR-007** (nouveau, Clarifications 2026-09-24) : le système DOIT permettre à un utilisateur authentifié de rôle Opérateur ou Administrateur de modifier la conformité d'un `Record` à tout moment après le scan initial, cette valeur faisant foi sur celle rapportée par le lecteur au moment du scan. Chaque modification DOIT être enregistrée dans une table d'historique dédiée (ancienne valeur, nouvelle valeur, auteur, date), ce qui conserve aussi le verdict initial du lecteur (voir spec `005`, Clarifications 2026-09-24).

### Key Entities

- **Record** (`record` table) : `id`, `picker` (nullable), `tag` (obligatoire), `reader` (obligatoire), `compliant` (boolean, colonne `conformity`), `creationDate`, `comment` (jamais renseigné par ce flux — toujours `null` en sortie de `registerScan`, evidence `TagService.java:60` lit `saved.getComment()` qui n'a jamais été positionné). Evidence: `RecordEntity.java`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (testé) : un scan avec un tag inconnu crée le tag et renvoie une réponse dont `uid`/`isCompliant`/`processedAt` correspondent à la requête et à l'horodatage de sauvegarde. Evidence: `TagServiceTest.java:44-68`.
- **SC-002** (testé) : un scan sans lecteur authentifié échoue immédiatement (`IllegalArgumentException` côté service, traduit en erreur HTTP par Spring — [NEEDS CLARIFICATION: quel code HTTP exact est renvoyé en pratique ? Aucun test d'intégration HTTP ne le vérifie, seul le test unitaire de service vérifie l'exception Java]). Evidence: `TagServiceTest.java:70-74`, `TagService.java:38` (`Assert.notNull`).
- **SC-003** (testé) : le `pickerId` du `Record` reflète bien le cueilleur du seau du tag scanné. Evidence: `TagServiceTest.java:76-97`.
- **SC-004**: [NEEDS CLARIFICATION: aucune cible de latence/débit n'est documentée pour ce endpoint, pourtant le plus sensible au temps réel (lecteur matériel en ligne de production).]

## Assumptions

- "Lecteur" (matériel physique) et `ReaderEntity` (enregistrement logique côté serveur) sont assimilés l'un à l'autre dans ce document, conformément au code.
- **Confirmé (Clarifications 2026-09-24)** : le diagramme de cycle de vie (`doc/20241103-tags_lifecycle_diagram.puml`) et l'enum `CHEAT` (`doc/20251013-database_diagram.puml:49`) sont tous deux obsolètes et ne décrivent pas le modèle cible ; le modèle cible reste le plus simple des deux (scan direct, booléen).

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| Cycle de vie du Tag en 3 états avec transitions contrôlées (`doc/20241103-tags_lifecycle_diagram.puml`) | Aucun champ d'état, aucune transition contrôlée ; comportement confirmé comme cible (Clarifications 2026-09-24) — diagramme abandonné | `TagEntity.java`, `TagService.java:36-62` |
| `conformity` = `ENUM('OK','NOK','CHEAT')` (`doc/20251013-database_diagram.puml:49`) | `compliant` est un `boolean` (2 valeurs) ; confirmé comme modèle cible (Clarifications 2026-09-24) — diagramme BDD à corriger sur ce point | `RecordEntity.java:48-49` |
| Aucune mention d'une double origine de la conformité (lecteur ET opérateur) | Deux mécanismes distincts peuvent écrire `compliant` ; résolu (Clarifications 2026-09-24) — la relecture humaine (PATCH) fait foi sur le scan matériel | `TagService.java:45`, `RecordService.java:60-66` |
