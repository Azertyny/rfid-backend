# Feature Specification: Consultation des lectures et bascule de conformité

**Feature Branch**: `005-consultation-lectures-conformite` (the as-is part was reverse-engineered from the codebase)

**Created**: 2026-09-24

**Status**: Delivered — as-is behavior plus the targets of the 2026-09-24 / 2026-09-25 clarifications (history, idempotency, FR-006 lock, history route, SC-003)

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

## Clarifications

### Session 2026-09-24

- Q: doc/ assigns the compliance-review use case to an "Opérateur" actor, but only "Administrateur" is defined and the code has no roles. Is the Opérateur a distinct role from the Administrateur? → A: Distinct, restricted role (recommended) — production-line staff who can only view the last reads and toggle compliance; the future auth system needs at least 2 roles.
- Q: reader.html sends the reader's x-api-token on both /records calls, but the backend never checks it. How should these two routes be protected? → A: Opérateur user login (recommended) — both routes require an authenticated user with role Opérateur or Administrateur; reader tokens stay machine-only.
- Q: When an Opérateur overrides a record's compliance, nothing records who changed it, when, or the reader's original verdict. Should overrides be traced? → A: Keep who/when + original (recommended) — store the reader's original verdict plus the author and time of the override.
- Q: Should compliance overrides be stored as extra columns on the record table, or in a separate table? → A: Separate table (recommended) — a dedicated history table keeps every change, not just the latest, so disputes with pickers can be resolved.

### Session 2026-09-25

- Q: If the reader scans the same tag again inside the duplicate window after an Opérateur has already changed the record's compliance, should that scan still be able to mark the record non-compliant? → A: No (A) — a record changed by an Opérateur is locked: duplicate scans no longer change its compliance.
- Q: Who should be able to see the history of compliance changes, and where? → A: B — new read-only route `GET /api/records/{recordId}/conformity-history`, Administrateurs only.
- Q: When a compliance update sends the value the record already has, should a history entry still be created? → A: A — no history entry and nothing changes; still `204` (idempotent).
- Q: What is the longest a production-line screen should wait for its list of the last 10 reads (`GET /api/records/readers/{uid}`) during the season? → A: B — 95% of list calls answered in under 200 ms server-side, with 1 to 3 screens polling every 500 ms and a season's worth of records; add an index if needed.
- Q (asked on spec `008`, revises the 2026-09-24 answer "reader tokens stay machine-only"): how should an Opérateur at the line's kiosk touch screen use these routes without typing a login and password? → A: The kiosk's `reader.html` authenticates with the line reader's own token (passed by the kiosk launcher in the URL fragment); the token only opens that reader's reads and `Record`s, and conformity changes made this way are credited to the reader. Logged-in Opérateurs and Administrateurs keep their access. See spec `008`, FR-005a/FR-005b.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consulter les 10 dernières lectures d'un lecteur (Priority: P1)

Un opérateur, sur l'écran dédié à un lecteur RFID, voit les 10 derniers tags scannés par ce lecteur.

**Evidence**: `controller/RecordController.java:22-25` (`listLatestRecordsForReader`), `service/RecordService.java:39-53`, `front/reader.html:191-242` (`fetchRecords`, `renderBoxes`).

**Acceptance Scenarios**:

1. **Given** un lecteur existant identifié par son `uid` (paramètre de chemin nommé `readerId` mais résolu par nom/uid, pas par UUID technique), **When** `GET /api/records/readers/{readerId}` est appelé, **Then** le système renvoie au plus les 10 `Record` les plus récents de ce lecteur, triés du plus récent au plus ancien. Evidence: `RecordService.java:41,44`, `repository/RecordRepository.java:22-24` (`findTop10ByReader_NameOrderByCreationDateDesc`). Test: `RecordApiTest.listLatest_returnsAtMostTenNewestFirst`.
2. **Given** le `uid` ne correspond à aucun lecteur, **When** la requête est envoyée, **Then** `404` (`ReaderNotFoundException`). Evidence: `RecordService.java:41-42`. Test: `RecordApiTest.listLatest_unknownReader_returns404`.

---

### User Story 2 - Basculer la conformité d'une lecture (Priority: P1)

Un opérateur clique sur un tag affiché à l'écran pour en inverser la conformité.

**Evidence**: `RecordController.java:27-32` (`updateRecordConformity`), `RecordService.java:71-89`, `front/reader.html:244-264` (`toggleCompliance`).

**Acceptance Scenarios**:

1. **Given** un `recordId` existant, **When** `PATCH /api/records/{recordId}/conformity` est appelé avec `{"isCompliant": <bool>}`, **Then** le `Record` est mis à jour avec cette valeur exacte et `204` est renvoyé. Evidence: `RecordService.java:73-88`.
2. **Given** l'inversion perçue côté utilisateur ("clique pour inverser"), **When** on regarde qui calcule la valeur inverse, **Then** ce n'est **pas** le serveur : l'API exige la valeur finale explicite (`isCompliant` obligatoire, `api.yaml:1173-1181`) ; c'est `front/reader.html` (fonction `toggleCompliance`) qui lit la valeur actuelle affichée et envoie son inverse. Le backend n'offre aucune opération "toggle" atomique ; le verrou de ligne et l'idempotence (scénario 4) rendent sans effet un second envoi de la même valeur. Evidence: `api.yaml:431-462`, `front/reader.html:244-264`.
3. **Given** le `recordId` n'existe pas, **When** la requête est envoyée, **Then** `404` (`RecordNotFoundException`). Evidence: `RecordService.java:74-75`.
4. **Given** un `Record` dont `compliant` vaut déjà la valeur envoyée (double clic, ou deux Opérateurs cliquant presque en même temps), **When** `PATCH /api/records/{recordId}/conformity` est appelé, **Then** `204`, aucune modification ni entrée d'historique (Clarifications 2026-09-25, **Livré** : `RecordService.java:76-78`, verrou `RecordRepository.findWithLockById`).
5. **Given** un `Record` conforme, **When** un Opérateur connecté appelle `PATCH /api/records/{recordId}/conformity` avec `{"isCompliant": false}`, **Then** `204`, le `Record` devient non conforme et une entrée d'historique est créée : ancienne valeur `true`, nouvelle valeur `false`, auteur = cet Opérateur, date de la modification (Clarifications 2026-09-24, **Livré** : `RecordService.java:79-88`).
6. **Given** un `Record` conforme qui a au moins une entrée d'historique, **When** son lecteur relit le même tag dans la fenêtre de doublon avec `isCompliant: false`, **Then** la réponse porte `"Duplicate read ignored"` et la valeur courante du `Record`, qui reste conforme, sans nouvelle entrée d'historique (FR-006, Clarifications 2026-09-25, **Livré** : `TagService.java:111-116`).
7. **Given** un `Record` du lecteur L, **When** le kiosque de la ligne L appelle `PATCH /api/records/{recordId}/conformity` avec le jeton du lecteur L (`x-api-token`, sans session), **Then** `204` et l'entrée d'historique a pour auteur le lecteur L ; avec le jeton d'un autre lecteur, `403` et aucune modification (Clarifications 2026-09-25, spec `008` FR-005a, **Livré** : `RecordService.updateRecordConformity`, `KioskReaderTokenSecurityTest`).

---

### User Story 3 - Consulter l'historique de conformité d'une lecture (Priority: P2, Clarifications 2026-09-25)

Un Administrateur consulte toutes les modifications manuelles de conformité d'une lecture pour régler un litige avec un cueilleur.

**Evidence**: `RecordController.java:34-37` (`listRecordConformityChanges`), `RecordService.java:91-115`, `SecurityConfig.java:126-127`, `api.yaml:463-489`. Tests: `RecordApiTest.history_*`, `RecordServiceTest.listConformityChanges_*`, `AccessMatrixSecurityTest`.

**Acceptance Scenarios**:

1. **Given** un `recordId` existant modifié n fois, **When** un Administrateur appelle `GET /api/records/{recordId}/conformity-history`, **Then** `200` avec les n modifications (ancienne valeur, nouvelle valeur, auteur, date), triées de la plus ancienne à la plus récente. L'auteur est un Utilisateur ou, pour une modification faite au kiosque, un Lecteur ; la réponse permet de distinguer les deux (Clarifications 2026-09-25, **Livré** : champs `authorType` / `authorReaderUid` de `ConformityChange`, `RecordService.toConformityChange`).
2. **Given** un `recordId` existant jamais modifié, **When** la requête est envoyée, **Then** `200` avec une liste vide.
3. **Given** le `recordId` n'existe pas, **When** la requête est envoyée, **Then** `404`.
4. **Given** un utilisateur de rôle Opérateur, **When** il appelle cette route, **Then** `403`.

### Edge Cases

- **Incohérence des acteurs documentés** : `doc/20251116-use_cases.md:108` attribue ce cas d'usage à l'acteur "Opérateur", mais la section `## Acteurs` (`doc/20251116-use_cases.md:1-3`) ne définit **que** "Administrateur". "Opérateur" est utilisé sans jamais être déclaré. **Résolu (2026-09-24)** : rôle distinct — l'Opérateur est un rôle restreint (consultation des dernières lectures et bascule de conformité uniquement), distinct de l'Administrateur. Rôles livrés par la spec `008` (`entity/Role`) ; la section "Acteurs" de `doc/` déclare désormais l'Opérateur.
- **Jeton de lecteur sur les routes `/records`** (historique) : avant la spec `008`, `front/reader.html` joignait l'en-tête `x-api-token` du lecteur à ces deux appels, alors que les routes étaient en `permitAll()` et n'exploitaient jamais ce jeton. **Livré (spec `008`)** : ces routes exigent une connexion utilisateur (rôle Opérateur ou Administrateur) (`SecurityConfig.java:128`), et `reader.html` passe par `apiFetch` (`front/auth.js`), avec la session et l'en-tête CSRF, sans jeton de lecteur. **Révisé (Clarifications 2026-09-25, spec `008` FR-005a/FR-005b, **Livré** : chaîne kiosque de `SecurityConfig`, `front/auth.js`)** : en mode kiosque, `reader.html` s'authentifie avec le jeton de son lecteur, reçu à l'exécution par le fragment d'URL (jamais dans les fichiers de `front/`), et n'accède qu'aux lectures et `Record`s de ce lecteur ; une page ouverte par un utilisateur connecté garde la session.
- **Traçabilité de la modification manuelle** : avant cette feature, la bascule de conformité écrasait sans trace le `compliant` initial du scan (voir spec `004`). **Livré (Clarifications 2026-09-24)** : chaque modification effective crée une ligne dans `record_conformity_change` (ancienne valeur, nouvelle valeur, auteur, date), sans perdre les modifications intermédiaires (`entity/RecordConformityChangeEntity.java`, `RecordService.java:79-88`). Le verdict initial du lecteur est l'ancienne valeur de la première modification. La valeur de l'Opérateur reste celle qui fait foi (spec `004`).
- **Lecture en double après modification manuelle** : avant cette feature, une relecture du même tag par le même lecteur dans la fenêtre de doublon (spec `004`, FR-008) pouvait repasser le `Record` à non conforme, écrasant sans trace la décision de l'Opérateur. **Livré (Clarifications 2026-09-25)** : dès qu'un `Record` a au moins une modification de conformité, une lecture en double ne modifie plus sa conformité (elle reste ignorée, réponse inchangée par ailleurs) (`TagService.java:111-116`). La recherche du doublon et la modification manuelle prennent le même verrou de ligne (`RecordRepository.java:26-40`), donc ne s'entremêlent pas.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT renvoyer les 10 derniers `Record` d'un lecteur donné par son `uid`, triés par date décroissante. Evidence: `RecordRepository.java:22-24`.
- **FR-002**: Le système DOIT renvoyer `404` si le `uid` de lecteur est inconnu. Evidence: `RecordService.java:41-42`.
- **FR-003**: Le système DOIT permettre de fixer explicitement la conformité d'un `Record` existant via une valeur booléenne fournie par le client (pas d'inversion serveur). Evidence: `RecordService.java:71-89`. **Livré (Clarifications 2026-09-24, précisée 2026-09-25)** : chaque modification effective (nouvelle valeur différente de la valeur courante) DOIT créer une entrée dans l'historique des modifications de conformité (ancienne valeur, nouvelle valeur, auteur, date) ; `Record.compliant` porte la valeur courante, qui fait foi (spec `004`). Une requête qui renvoie la valeur courante est idempotente : `204`, sans écriture ni entrée d'historique.
- **FR-004**: Les routes `GET /api/records/readers/{readerId}` et `PATCH /api/records/{recordId}/conformity` DOIVENT exiger soit un utilisateur authentifié de rôle Opérateur ou Administrateur, soit le jeton `x-api-token` du lecteur concerné (kiosque tactile de la ligne) : ce jeton ne donne accès qu'aux lectures et `Record`s de ce lecteur (`403` sinon). **Livré (spec `008`)** pour la connexion utilisateur : règle `/api/records/**` de `SecurityConfig`, couverte par `AccessMatrixSecurityTest`. **Livré (Clarifications 2026-09-25, spec `008` FR-005a)** pour le jeton de lecteur : chaîne kiosque de `SecurityConfig` (`@Order(2)`), contrôle du lecteur dans `RecordService`, couverts par `KioskReaderTokenSecurityTest` et `AccessMatrixSecurityTest`. Historique : avant la spec `008`, ces routes étaient en `permitAll()` et l'en-tête `x-api-token` envoyé par `front/reader.html` n'était jamais vérifié.
- **FR-005** (nouveau, Clarifications 2026-09-24) : le système DOIT distinguer au moins deux rôles : Administrateur (toutes les fonctions) et Opérateur (consultation des lectures et bascule de conformité uniquement). **Livré (spec `008`)** : `entity/Role` (`ADMINISTRATEUR`, `OPERATEUR`), matrice d'accès de `SecurityConfig`.
- **FR-006** (nouveau, Clarifications 2026-09-25) : une lecture en double (spec `004`, FR-008) NE DOIT PAS modifier la conformité d'un `Record` qui possède au moins une modification de conformité ; la valeur fixée par l'Opérateur fait foi et l'historique ne contient que des modifications manuelles. **Livré** : `TagService.java:111-116`.
- **FR-007** (nouveau, Clarifications 2026-09-25) : le système DOIT exposer l'historique des modifications de conformité d'un `Record` via `GET /api/records/{recordId}/conformity-history`, en lecture seule, réservé au rôle Administrateur. Cette règle DOIT précéder la règle générique `/api/records/**` (Administrateur + Opérateur) de la matrice de la spec `008`. **Livré** : `RecordService.java:91-115`, `SecurityConfig.java:126-128` ; matrice de la spec `008` mise à jour.

### Key Entities

- **Record** (déjà décrit en spec `004`) : consommé ici en lecture (les 10 derniers d'un lecteur) et modifié en écriture (`compliant` uniquement). `compliant` reste la valeur courante ; l'historique des modifications est porté par une entité séparée (ci-dessous). Index `(reader_id, creation_date)` ajouté pour la liste des 10 derniers (SC-003).
- **Modification de conformité** (`RecordConformityChangeEntity`, table `record_conformity_change`, Clarifications 2026-09-24) : une ligne par modification manuelle — `record` (FK vers `Record`), ancienne valeur, nouvelle valeur, auteur, date. L'auteur est soit l'utilisateur authentifié, soit le lecteur dont le jeton a servi (kiosque, Clarifications 2026-09-25, **Livré** : colonne `author_reader_id` ; `author_id` rendu facultatif au démarrage par `configuration/ConformityAuthorSchemaUpgrade`). Relation : un `Record` a 0..n modifications. Le verdict initial du lecteur se lit dans l'ancienne valeur de la première modification (ou dans `compliant` s'il n'y en a aucune).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** : la liste renvoyée ne dépasse jamais 10 éléments, conformément à `doc/20251116-use_cases.md:111` ("Les 10 derniers TAGs lus par lecteur sont affichés"). Evidence: `RecordRepository.java:22-24` (`findTop10By...`). Test: `RecordApiTest.listLatest_returnsAtMostTenNewestFirst` (12 lectures → 10 renvoyées, de la plus récente à la plus ancienne).
- **SC-002** : le module est couvert par des tests automatisés : `RecordApiTest` (HTTP, 13 cas), `RecordServiceTest` (unitaire, 5 cas), les cas FR-006 de `TagServiceTest`, et la route d'historique dans `AccessMatrixSecurityTest`.
- **SC-003** (Clarifications 2026-09-25) : 95 % des appels `GET /api/records/readers/{uid}` reçoivent leur réponse en moins de 200 ms côté serveur, avec 1 à 3 écrans `reader.html` interrogeant chacun toutes les 500 ms (`front/config.js`, `POLLING_INTERVAL`) et une table `record` contenant le volume d'une saison. Même seuil et même charge que le SC-004 de la spec `004`. Si l'index existant `(reader_id, tag_id, creation_date)` ne suffit pas pour « les 10 plus récents d'un lecteur », un index adapté DOIT être ajouté. **Livré, mesuré via quickstart.md (2026-09-25)** : index `idx_record_reader_date (reader_id, creation_date)` et tags chargés dans la même requête ; sur H2 avec 200 000 lectures pour le lecteur, 3 boucles interrogeant toutes les 500 ms pendant 2 min (720 appels) : p50 7 ms, **p95 13 ms**. Volume de saison supposé : 200 000 lectures par lecteur (research R13).

## Assumptions

- **Confirmé (Clarifications 2026-09-24)** : "Opérateur" et "Administrateur" sont deux rôles distincts ; l'Opérateur a des droits restreints.
- Le paramètre de chemin `readerId` correspond en réalité à l'`uid`/`name` du lecteur, pas à son identifiant technique UUID (`ReaderEntity.id`) — confirmé par `RecordService.java:41` (`readerRepository.findByName`).

## Drift vs `doc/`

| Point documenté (`doc/20251116-use_cases.md:102-115`) | Comportement réel | Fichiers |
|---|---|---|
| Acteur "Opérateur" | Rôle distinct et restreint (Clarifications 2026-09-24), livré par la spec `008` ; désormais déclaré dans la section `## Acteurs` de `doc/` | `doc/20251116-use_cases.md:1-4,109`, `entity/Role.java` |
| "Le système change le champ de conformité à l'inverse de la valeur précédente" | L'inversion est calculée côté front (`reader.html`), le backend applique la valeur reçue, trace chaque changement effectif et ignore un renvoi de la valeur courante | `RecordService.java:71-89`, `front/reader.html:244-264` |
| (implicite) action nécessitant d'être un opérateur identifié | Connexion utilisateur exigée, rôle Opérateur ou Administrateur (livré par la spec `008`) ; l'historique est réservé à l'Administrateur (FR-007) | `SecurityConfig.java:126-128` |
