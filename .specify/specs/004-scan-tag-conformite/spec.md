# Feature Specification: Scan d'un TAG par un lecteur (déclaration de conformité)

**Feature Branch**: `004-scan-tag-conformite` (spec first reverse-engineered from the codebase, then extended by the 2026-09-24 clarifications)

**Created**: 2026-09-24

**Status**: Delivered (2026-09-24) — FR-002 (UID vide → `400`) and FR-008 (dédoublonnage) implemented; see [plan.md](plan.md)

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

## Clarifications

### Session 2026-09-24

- Q: doc/'s tag lifecycle diagram describes 3 states (IDLE → EnAttente → Comptabilisé) with controlled transitions, but the code has no state field anywhere and a scan just creates the tag and record in one step. Should this state machine actually be built, or is the diagram stale documentation that should be dropped? → A: Drop the diagram (recommended) — treated as obsolete/aspirational; the simpler "scan creates tag+record directly" model is the target design. No state machine to build.
- Q: Compliance on a Record can currently be set two different ways with no arbitration rule: the reader hardware reports isCompliant at scan time, and an operator can later overwrite it via PATCH /records/{id}/conformity. Which should be the authoritative source going forward? → A: Human review is authoritative (recommended) — the reader's scan-time value is an initial/default flag; the operator's manual review is the final word and may override it at any time.
- Q: doc/'s database diagram documents conformity as a 3-value ENUM('OK','NOK','CHEAT'), but the code only has a boolean — the CHEAT state doesn't exist anywhere. Should CHEAT be dropped too (consistent with dropping the lifecycle diagram), or is it still a real state to add? → A: Drop CHEAT, keep boolean (recommended) — the 3-value ENUM in the DB diagram is stale; boolean compliant/non-compliant is the confirmed target model.
- Q: When the same reader sends the same tag several times in a row (RFID hardware re-reads a tag while it stays in range), should each scan create its own Record? → A: No — a repeat of the same tag from the same reader within a short window (10 s by default) is ignored: the reader gets the usual response but no new Record is created. Separate passes outside the window still count.
- Q: How fast must the server answer a reader's scan (`POST /api/tags/scan`) under normal load? → A: p95 < 200 ms.
- Q: When a reader sends an empty or blank tag UID, what should the server return? → A: `400 Bad Request` with an error message, and no Record created.
- Q: Within the dedup window, what happens when a repeat scan's verdict differs from the existing Record's? → A: The non-compliant verdict wins: a repeat saying non-compliant turns a compliant Record non-compliant, with no new Record; a repeat saying compliant never changes a non-compliant Record.
- Q: What does "normal load" mean for the SC-004 latency target? → A: 1 to 3 readers at the same time, each sending scans back to back.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un lecteur RFID signale la lecture d'un tag (Priority: P1)

Un lecteur RFID physique, authentifié par son jeton d'API, envoie l'UID d'un tag scanné et son propre verdict de conformité ; le système enregistre un `Record`.

**Evidence**: `controller/TagController.java:31-39` (`scanTag`), `service/TagService.java:64-120`, `security/ReaderApiTokenAuthenticationFilter.java`, `api.yaml:260-293`.

**Acceptance Scenarios**:

1. **Given** un en-tête `x-api-token` valide (correspondant à un `ReaderEntity.apitoken`), **When** `POST /api/tags/scan` est appelé avec `{"uid": "...", "isCompliant": true|false}`, **Then** le système authentifie la requête (`ReaderAuthentication`), résout le lecteur depuis le contexte de sécurité, et crée un `RecordEntity` lié au tag, au lecteur et — si le tag est déjà associé à un seau lui-même affecté à un cueilleur — au cueilleur. Evidence: `TagController.java:31-39`, `TagService.java:64-95`.
2. **Given** l'en-tête `x-api-token` est absent ou invalide, **When** `POST /api/tags/scan` est appelé, **Then** `401` avant même d'atteindre le contrôleur (filtre de sécurité). Evidence: `ReaderApiTokenAuthenticationFilter.java:49-62`.
3. **Given** l'UID scanné n'existe pas encore en base, **When** le scan est traité, **Then** un nouveau `TagEntity` est créé à la volée, **sans** seau associé. Evidence: `TagService.java:70,77`.
4. **Given** le tag scanné est associé à un seau lui-même affecté à un cueilleur, **When** le scan est traité, **Then** le `Record` créé porte le `pickerId` de ce cueilleur ; sinon `pickerId` est `null`. Evidence: `TagService.java:79,83`, test `TagServiceTest.java:93-114`.
5. **Given** un lecteur authentifié, **When** `POST /api/tags/scan` est appelé avec un `uid` vide ou composé uniquement d'espaces, **Then** `400` avec un `detail` nommant `uid`, sans tag ni `Record` créé (FR-002).
6. **Given** un lecteur `PRODUCTION` a créé un `Record` pour un tag il y a moins de 10 s, **When** il scanne à nouveau ce tag, **Then** `200` avec `message: "Duplicate read ignored"` et le `processedAt` du `Record` existant, sans nouveau `Record` ; un autre lecteur scannant ce tag crée bien son propre `Record` (FR-008).
7. **Given** ce même `Record` est conforme, **When** le scan répété déclare `isCompliant: false`, **Then** le `Record` passe à non conforme et la réponse porte `isCompliant: false` ; un scan répété conforme ne le remet jamais à conforme (FR-008).

### Edge Cases

- **Relectures en rafale du même tag** : un lecteur RFID relit un tag tant qu'il reste dans son champ. Aujourd'hui chaque appel crée un `Record` (`TagService.java` `registerScan`), si bien qu'un seul passage de seau peut compter plusieurs fois. **Livré (2026-09-24)** : dédoublonnage par couple (lecteur, tag) sur une fenêtre de 10 s, voir FR-008 ; un scan répété renvoie `200` avec `message: "Duplicate read ignored"` et ne crée pas de `Record`, et un scan répété non conforme abaisse le `Record` existant. Un même tag lu par deux lecteurs différents dans la fenêtre donne bien deux `Record`. Limite connue : deux requêtes identiques traitées exactement en même temps peuvent créer deux `Record` ; un lecteur envoyant ses lectures l'une après l'autre ne le déclenche pas (research R5).
- **Aucune machine à états** : `doc/20241103-tags_lifecycle_diagram.puml` documente un cycle de vie du Tag en 3 états (`IDLE` → `EnAttente` → `Comptabilisé`), avec des transitions explicites ("Enregistrement", "Affectation", "Démarrage de l'activité liée", "Lecture pendant l'activité"). **Aucun champ d'état** n'existe sur `TagEntity` (`entity/TagEntity.java`) ni sur aucune autre entité du domaine, et aucune règle du code ne refuse un scan sous prétexte qu'un tag ne serait pas "en attente" d'une activité. Un scan crée le tag et le `Record` en une seule étape, sans jamais vérifier d'état préalable. Evidence: absence de champ `status`/`state` dans `TagEntity.java`, `BucketEntity.java`, `RecordEntity.java`. **Résolu (2026-09-24)** : obsolète — le diagramme de cycle de vie est abandonné ; le modèle cible reste "un scan crée directement le tag et le `Record`", sans machine à états à construire.
- **Double source de vérité pour la conformité** : ce endpoint laisse le **lecteur matériel** déclarer lui-même `isCompliant` (`ScanTagRequest.isCompliant`, `api.yaml:901-915`) au moment du scan. Le module `005-consultation-lectures-conformite` permet ensuite à un opérateur humain de **modifier** cette même valeur a posteriori (`PATCH /records/{id}/conformity`). Le code ne documente ni ne garantit laquelle des deux sources fait foi en cas de désaccord — la dernière écriture gagne simplement. **Résolu (2026-09-24)** : la relecture humaine fait foi — la valeur `isCompliant` du scan matériel n'est qu'un indicateur initial ; la bascule manuelle par un opérateur (`PATCH /records/{id}/conformity`, voir spec `005`) est la valeur finale et peut remplacer celle du lecteur à tout moment. Ce remplacement est réservé aux rôles Opérateur et Administrateur, et chaque modification est enregistrée dans une table d'historique dédiée, ce qui conserve le verdict initial du lecteur (spec `005`, Clarifications 2026-09-24).
- **Enum de conformité non implémenté** : `doc/20251013-database_diagram.puml:49` documente `conformity : ENUM('OK','NOK','CHEAT')` — trois valeurs possibles. `RecordEntity.compliant` (`entity/RecordEntity.java:48-49`) est un simple `boolean` : seules deux valeurs sont représentables, l'état `CHEAT` documenté n'existe nulle part dans le code. **Résolu (2026-09-24)** : abandonné — cohérent avec l'abandon du diagramme de cycle de vie, `CHEAT` n'est pas un état à ajouter ; le booléen `compliant` reste le modèle cible.
- **Filtre de sécurité par chemin exact codé en dur** : `ReaderApiTokenAuthenticationFilter` ne protège que la chaîne exacte `/api/tags/scan` après retrait du context-path (`ReaderApiTokenAuthenticationFilter.java:26-36`). Toute évolution du chemin (context-path applicatif, réécriture par un reverse proxy, nouvelle route) contournerait silencieusement la protection. Non testé.
- **Couche sécurité et validation testées** : `security/ReaderScanSecurityTest.java` exerce le filtre et `TagController` par de vraies requêtes HTTP (jeton absent, inconnu, lecteur désactivé, rotation de jeton, mode `ENREGISTREMENT`, UID vide) ; `controller/TagScanApiTest.java` couvre le rejet d'un UID vide (FR-002) et le dédoublonnage (FR-008).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT exiger un en-tête `x-api-token` valide pour `POST /api/tags/scan`, et renvoyer `401` sinon. Evidence: `ReaderApiTokenAuthenticationFilter.java:49-62`, `SecurityConfig.java:66`.
- **FR-002**: Le système DOIT créer le tag scanné s'il est inconnu, sans validation de format d'UID au-delà de "non vide". Evidence: `TagService.java:67-77`. **Décision (Clarifications 2026-09-24)** : un UID vide ou composé uniquement d'espaces DOIT être rejeté par `400 Bad Request` avec un message d'erreur, sans créer de tag ni de `Record` **Livré** : `pattern: '.*\S.*'` sur `ScanTagRequest.uid` (`api.yaml`), la validation générée rejette la requête avant le contrôleur dans les deux modes de lecteur, et `ApiExceptionHandler.handleInvalidBody` renvoie un `ProblemDetail` dont `detail` nomme le champ (research R1). Tests : `TagScanApiTest`, `ReaderScanSecurityTest.scan_fromEnregistrementReader_withBlankUid_returns400`.
- **FR-003**: Le système DOIT créer un `Record` pour chaque scan non dédoublonné (voir FR-008), portant le tag, le lecteur authentifié, et le cueilleur déduit du seau du tag (le cas échéant). Evidence: `TagService.java:79-87`. **Décision (spec `003`, Clarifications 2026-09-24)** : uniquement pour un lecteur en mode `PRODUCTION` ; un lecteur en mode `ENREGISTREMENT` utilise le même endpoint, mais ses scans deviennent des lectures temporaires d'enregistrement, sans `Record`. Le lecteur lui-même n'a rien à changer. **Livré par la feature `003`** : l'aiguillage est dans `TagController.scanTag` ; le lecteur reçoit la réponse habituelle avec `isCompliant: true` (spec `003`, research R2).
- **FR-004**: Le système DOIT accepter la valeur `isCompliant` fournie par le lecteur telle quelle, sans recalcul serveur, sauf pour un scan dédoublonné (FR-008). Evidence: `TagService.java:69,84`.
- **FR-005**: Le système DOIT horodater le `Record` à la persistance (`creationDate`, non modifiable). Evidence: `RecordEntity.java:54-56`.
- **FR-006**: Le système NE distingue PAS d'état "CHEAT" ; `compliant` est binaire. Evidence: `RecordEntity.java:51-52`. **Décision (Clarifications 2026-09-24)** : confirmé comme modèle cible — `doc/20251013-database_diagram.puml:49` est obsolète sur ce point.
- **FR-007** (nouveau, Clarifications 2026-09-24) : le système DOIT permettre à un utilisateur authentifié de rôle Opérateur ou Administrateur de modifier la conformité d'un `Record` à tout moment après le scan initial, cette valeur faisant foi sur celle rapportée par le lecteur au moment du scan. Chaque modification DOIT être enregistrée dans une table d'historique dédiée (ancienne valeur, nouvelle valeur, auteur, date), ce qui conserve aussi le verdict initial du lecteur (voir spec `005`, Clarifications 2026-09-24). **Porté par la spec `005`** : hors du périmètre de livraison de `004` (plan, research R0). Au 2026-09-24, ni la table d'historique ni la restriction de rôle ne sont livrées.

- **FR-008** (nouveau, Clarifications 2026-09-24) : le système DOIT ignorer un scan en mode `PRODUCTION` si un `Record` existe déjà pour le même tag **et** le même lecteur avec une `creationDate` de moins de 10 secondes (valeur par défaut, configurable). Dans ce cas, aucun `Record` n'est créé et le lecteur reçoit `200`, même format de réponse, sans erreur, pour que le firmware n'ait rien à changer : la réponse porte les valeurs du `Record` existant (`processedAt` = sa date de création) et `message: "Duplicate read ignored"`. Au-delà de la fenêtre, un nouveau scan crée un nouveau `Record`. **Verdict dans la fenêtre (Clarifications 2026-09-24)** : si le scan ignoré déclare `isCompliant: false` alors que le `Record` existant est conforme, ce `Record` passe à non conforme (le verdict non conforme l'emporte dans la fenêtre) ; l'inverse n'est jamais appliqué. Seule exception à FR-007 : dans la fenêtre suivant la création du `Record`, un scan répété non conforme peut abaisser une conformité déjà revue par un opérateur (research R2). **Livré** : `TagService.registerScan` (`findRecentRecord`, `ignoreDuplicate`), fenêtre `app.scan.duplicate-window` (`application.yml`), index `idx_record_reader_tag_date` sur `record` (research R2–R4). Tests : `TagServiceTest`, `TagScanApiTest`.

### Key Entities

- **Record** (`record` table) : `id`, `picker` (nullable), `tag` (obligatoire), `reader` (obligatoire), `compliant` (boolean, colonne `conformity`), `creationDate`, `comment` (jamais renseigné par ce flux — toujours `null` en sortie de `registerScan`, evidence `TagService.java:93` lit `saved.getComment()` qui n'a jamais été positionné). Evidence: `RecordEntity.java`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (testé) : un scan avec un tag inconnu crée le tag et renvoie une réponse dont `uid`/`isCompliant`/`processedAt` correspondent à la requête et à l'horodatage de sauvegarde. Evidence: `TagServiceTest.java:61-85`.
- **SC-002** (testé) : un scan sans lecteur authentifié échoue immédiatement : `401` au niveau HTTP, renvoyé par le filtre avant le contrôleur (`ReaderScanSecurityTest`) ; `IllegalArgumentException` si l'on appelle le service directement (`TagServiceTest.java:87-91`, `TagService.java` `Assert.notNull`). Un scan avec un UID vide renvoie `400` (Clarifications 2026-09-24, FR-002).
- **SC-003** (testé) : le `pickerId` du `Record` reflète bien le cueilleur du seau du tag scanné. Evidence: `TagServiceTest.java:93-114`.
- **SC-004** (Clarifications 2026-09-24) : 95 % des appels `POST /api/tags/scan` reçoivent leur réponse en moins de 200 ms côté serveur, recherche de dédoublonnage (FR-008) comprise, avec 1 à 3 lecteurs actifs en même temps envoyant chacun leurs scans à la suite (Clarifications 2026-09-24). **Mesuré** via [quickstart.md](quickstart.md) (2026-09-24, H2 local, 3 lecteurs en parallèle, 900 scans par passe) : p95 = 4,8 ms avec des UID distincts, 3,3 ms avec un UID répété (chemin de dédoublonnage). À refaire sur PostgreSQL avec une table `record` de taille saison avant la mise en production.

## Assumptions

- "Lecteur" (matériel physique) et `ReaderEntity` (enregistrement logique côté serveur) sont assimilés l'un à l'autre dans ce document, conformément au code.
- **Confirmé (Clarifications 2026-09-24)** : le diagramme de cycle de vie (`doc/20241103-tags_lifecycle_diagram.puml`) et l'enum `CHEAT` (`doc/20251013-database_diagram.puml:49`) sont tous deux obsolètes et ne décrivent pas le modèle cible ; le modèle cible reste le plus simple des deux (scan direct, booléen).

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| Cycle de vie du Tag en 3 états avec transitions contrôlées (`doc/20241103-tags_lifecycle_diagram.puml`) | Aucun champ d'état, aucune transition contrôlée ; comportement confirmé comme cible (Clarifications 2026-09-24) — diagramme abandonné | `TagEntity.java`, `TagService.java:64-95` |
| `conformity` = `ENUM('OK','NOK','CHEAT')` (`doc/20251013-database_diagram.puml:49`) | `compliant` est un `boolean` (2 valeurs) ; confirmé comme modèle cible (Clarifications 2026-09-24) — diagramme BDD à corriger sur ce point | `RecordEntity.java:51-52` |
| Aucune mention d'une double origine de la conformité (lecteur ET opérateur) | Deux mécanismes distincts peuvent écrire `compliant` ; résolu (Clarifications 2026-09-24) — la relecture humaine (PATCH) fait foi sur le scan matériel | `TagService.java:69`, `RecordService.java:61-66` |
