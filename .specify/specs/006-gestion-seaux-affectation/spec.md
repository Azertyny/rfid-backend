# Feature Specification: Gestion des seaux et affectation à un cueilleur

**Feature Branch**: `006-gestion-seaux-affectation`

**Created**: 2026-09-24

**Status**: Target — as-is spec updated by the 2026-09-24/25 clarifications, implemented on branch `006-gestion-seaux-affectation` ; amendement du 2026-10-11 (suppression d'un seau, FR-010, User Story 4) implémenté sur la branche `006-suppression-seau`

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

**Amendment input (2026-10-11)**: "Il faudrait avoir la possibilité de supprimer un seau"

## Clarifications

### Session 2026-09-24

- Q: doc/'s database diagram says a picker has at most one bucket, but the code doesn't enforce it. What should happen when a bucket is assigned to a picker who already has one? → A: Allow several buckets — a picker may have several buckets; the diagram's 0..1 cardinality is wrong and nothing needs enforcing.
- Q: Since a picker can have several buckets, how should the Picker API expose them (it currently has a single bucketNumber and crashes with two buckets)? → A: List of numbers (recommended) — replace bucketNumber with bucketNumbers, a list (empty when none).
- Q: There's no way to remove a picker from a bucket, yet deleting a picker is blocked until all their buckets are unassigned (spec 001). How should unassigning work? → A: DELETE /buckets/{id}/picker (recommended) — a dedicated route that clears the bucket's picker (204, or 404 if the bucket doesn't exist).
- Q: doc/ describes a web page for assigning buckets, but no such page exists in /front. Should that page be built? → A: API-only, external tool — same as spec 003: an external tool calls the API; no /front page to build, and doc/'s UI wording describes that tool. **Superseded** by the next bullet.
- Q (asked during `/speckit-plan` on auth and roles): how should the external tool authenticate? → A: It is not an external tool — bucket assignment is part of the web front, used by a logged-in Administrateur.

### Session 2026-09-25

- Q: If an Administrateur assigns a bucket that already belongs to another picker, what should the page do? → A: Reassignment allowed — the confirmation window names the current picker ("Seau n°X est actuellement affecté à <Prénom Nom>, le réaffecter ?"); the API keeps overwriting (`204`), no `409`.
- Q: Should the new bucket management page also let the Administrateur remove a picker from a bucket, or only assign one? → A: Assign and unassign — each assigned bucket has a "Désaffecter" button, with a confirmation window naming the current picker, calling `DELETE /api/buckets/{bucketId}/picker`.
- Q: Which automated tests are required for this feature to be done? → A: Backend tests only — `BucketService` unit tests plus controller/security tests; the front page is checked manually.
- Q: On the bucket page, how should the Administrateur choose the picker to assign? → A: A dropdown with text filtering, listing all pickers sorted by last name; the page loads every page of `GET /api/pickers` (`size` is capped at 100); no API change.

### Session 2026-10-11

- Q: When an Administrateur deletes a bucket that still has registered tags, what should happen? (FR-010) → A: Free the tags — the bucket is deleted and its tags stay known but without a bucket, to be registered again; the confirmation window gives the number of tags. Refusing would leave no way to empty a bucket, since a tag can only leave one by moving to another bucket or by being counted.
- Q: And when the bucket is still assigned to a picker? (FR-010) → A: Delete anyway — the picker loses this bucket, their past records keep their name; the confirmation window names the picker.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consulter les seaux et leurs tags (Priority: P1)

Un Administrateur consulte la liste de tous les seaux, avec les tags qui leur sont associés et le cueilleur assigné le cas échéant.

**Evidence**: `controller/BucketController.java:20-23` (`listBuckets`), `service/BucketService.java:35-67`, `api.yaml:271-284`.

**Acceptance Scenarios**:

1. **Given** des seaux existent, **When** `GET /api/buckets` est appelé, **Then** la réponse liste tous les seaux (triés par numéro croissant), chacun avec la liste des UID de tags associés et, si affecté, le cueilleur (nom/prénom/commentaire uniquement — pas son id). Evidence: `BucketService.java:35-67,100-106`.
2. **Given** un `bucketId` précis, **When** `GET /api/buckets/{bucketId}` est appelé, **Then** même structure pour ce seau uniquement, ou `404` si absent. Evidence: `BucketService.java:69-87`.
3. **Given** un Administrateur connecté, **When** il ouvre la page "Seaux" du front, **Then** chaque seau est affiché par numéro croissant avec le nombre de tags (UID visibles au survol) et le cueilleur affecté (Prénom Nom) ou "Non affecté".
4. **Given** aucun seau n'existe, **When** la page s'ouvre, **Then** elle affiche "Aucun seau. Les seaux sont créés à l'enregistrement des tags." avec un lien vers la page Tags.
5. **Given** un utilisateur Opérateur, **When** il ouvre la page "Seaux", **Then** l'accès est refusé et le lien "Seaux" n'apparaît pas dans sa navigation.

---

### User Story 2 - Affecter un seau à un cueilleur (Priority: P1)

Un administrateur affecte un seau existant à un cueilleur existant.

**Evidence**: `BucketController.java:30-35` (`assignBucketToPicker`), `BucketService.java:89-98`, `api.yaml:310-335`.

**Acceptance Scenarios**:

1. **Given** le seau et le cueilleur existent tous les deux, **When** `PUT /api/buckets/{bucketId}/picker` est appelé avec `{"pickerId": "..."}`, **Then** le seau est affecté à ce cueilleur (`204`). Evidence: `BucketService.java:89-98`.
2. **Given** le seau ou le cueilleur n'existe pas, **When** la requête est envoyée, **Then** `404` (`BucketNotFoundException` ou `PickerNotFoundException`). Evidence: `BucketService.java:91-94`.
3. **Given** le cueilleur ciblé possède déjà un autre seau, **When** l'affectation est effectuée, **Then** le seau lui est affecté en plus du précédent (`204`). **Résolu (2026-09-24)** : comportement cible — un cueilleur peut avoir plusieurs seaux. Voir Edge Cases pour l'impact sur le module cueilleurs.
4. **Given** le seau est déjà affecté à un autre cueilleur, **When** l'Administrateur le sélectionne dans la page et choisit un nouveau cueilleur, **Then** la fenêtre de confirmation indique le cueilleur actuel ("Seau n°X est actuellement affecté à <Prénom Nom>, le réaffecter ?") ; après validation, `PUT /api/buckets/{bucketId}/picker` remplace l'affectation (`204`) et l'ancien cueilleur n'a plus ce seau. (Clarifications 2026-09-25)
5. **Given** plus de 100 cueilleurs existent, **When** l'Administrateur ouvre la liste de choix du cueilleur, **Then** tous les cueilleurs y figurent (toutes les pages chargées), triés par nom, et la saisie de quelques lettres filtre la liste. (Clarifications 2026-09-25)
6. **Given** le seau est affecté au cueilleur A, **When** l'Administrateur ouvre "Réaffecter", **Then** A n'apparaît pas dans la liste de choix (on ne peut pas réaffecter un seau à son cueilleur actuel). (Clarifications 2026-09-25, analyse)

### User Story 3 - Désaffecter un seau depuis le front (Priority: P2)

Un Administrateur retire le cueilleur d'un seau depuis la page de gestion des seaux, par exemple pour pouvoir ensuite supprimer ce cueilleur (spec `001`). (Clarifications 2026-09-25)

**Acceptance Scenarios**:

1. **Given** un seau affecté à un cueilleur, **When** l'Administrateur clique sur "Désaffecter" puis confirme dans la fenêtre nommant le cueilleur actuel, **Then** `DELETE /api/buckets/{bucketId}/picker` est appelé (`204`) et le seau est affiché sans cueilleur.
2. **Given** la fenêtre de confirmation est ouverte, **When** l'Administrateur annule, **Then** aucune requête n'est envoyée et l'affectation est inchangée.
3. **Given** un seau sans cueilleur, **When** la page l'affiche, **Then** aucune action "Désaffecter" n'est proposée pour ce seau.

### User Story 4 - Supprimer un seau (Priority: P2)

Cible (Clarifications 2026-10-11, FR-010). Un Administrateur supprime un seau dont il n'a plus l'usage : créé par erreur (numéro mal saisi à l'enregistrement des tags, qui crée le seau), perdu ou retiré. Aujourd'hui un seau ne disparaît jamais de la page "Seaux".

**Why this priority**: les seaux s'accumulent dans la liste sans gêner le comptage ; gêne de lisibilité, pas de blocage.

**Acceptance Scenarios**:

1. **Given** un seau sans tag ni cueilleur, **When** l'Administrateur clique sur "Supprimer" puis confirme, **Then** le seau n'apparaît plus dans la liste des seaux ni dans son détail (`404`).
2. **Given** un seau n°12 avec 2 tags et affecté au cueilleur Jean Martin, **When** l'Administrateur clique sur "Supprimer", **Then** la fenêtre de confirmation indique que le seau n°12 contient 2 tags qui seront libérés et qu'il est affecté à Jean Martin ; après validation, le seau est supprimé, ses 2 tags n'ont plus de seau et le seau n'apparaît plus dans les numéros de seaux de Jean Martin.
3. **Given** la fenêtre de confirmation est ouverte, **When** l'Administrateur annule, **Then** rien n'est envoyé et le seau est inchangé.
4. **Given** des lectures passées comptées pour un tag de ce seau, **When** le seau est supprimé, **Then** ces lectures restent consultables avec le même cueilleur, la même activité et la même conformité, et les totaux du tableau de bord sont identiques à ceux d'avant la suppression.
5. **Given** un tag libéré par la suppression, **When** il est lu par un lecteur de production, **Then** il est traité comme tout tag sans seau (lecture sans cueilleur, spec `004`) ; **When** il est enregistré dans un seau, **Then** il y est ajouté sans demande de confirmation de déplacement.
6. **Given** le seau n°12 supprimé, **When** des tags sont ensuite enregistrés sous le numéro 12, **Then** un nouveau seau n°12 est créé, sans cueilleur, ne contenant que ces tags.
7. **Given** le cueilleur Jean Martin n'avait que ce seau, **When** le seau est supprimé, **Then** Jean Martin peut être supprimé (spec `001`) sans désaffectation préalable.
8. **Given** un identifiant de seau inconnu ou déjà supprimé, **When** la suppression est demandée, **Then** `404`.
9. **Given** un Opérateur connecté, **When** il tente de supprimer un seau, **Then** `403` ; un appelant non connecté reçoit `401`.

### Edge Cases

- **Cardinalité 1 cueilleur ↔ 0..1 seau non appliquée** : `doc/20251013-database_diagram.puml:66` déclare `picker ||--o| bucket`, soit un cueilleur associé à **au plus un** seau. Rien dans le code n'impose cette contrainte : `BucketEntity.picker` (`BucketEntity.java:36-38`) est une simple FK `@ManyToOne`, **sans contrainte d'unicité** sur la colonne `picker_id`, et `BucketService.assignBucketToPicker` (`BucketService.java:89-98`) n'effectue **aucune vérification** qu'un cueilleur n'a pas déjà un seau avant d'en affecter un second. Rien ne désaffecte non plus l'éventuel seau précédent de ce cueilleur. Un même cueilleur peut donc se retrouver, en base, affecté à plusieurs seaux simultanément — ce qui contredit directement `doc/20251013-database_diagram.puml`. **Résolu (2026-09-24)** : le diagramme est obsolète — un cueilleur peut avoir plusieurs seaux, aucune contrainte d'unicité n'est à ajouter. **Diagramme corrigé (006)** : `picker ||--o{ bucket`.
- **Le module cueilleurs suppose encore un seul seau par cueilleur** (constat, 2026-09-24) : `PickerService.listPickers` construit une map cueilleur → numéro de seau avec `Collectors.toMap` (`PickerService.java:45-47`), qui lève une exception sur clé dupliquée ; `GET /api/pickers` renvoie donc une erreur `500` pour toute la page dès qu'un cueilleur listé a deux seaux. `PickerService.getPicker` utilise `BucketRepository.findByPicker` (`BucketRepository.java:16`, retour `Optional` unique), qui échoue aussi avec deux résultats : `GET /api/pickers/{id}` renvoie `500` pour ce cueilleur. Le schéma `Picker.bucketNumber` (`api.yaml:476-479`) ne porte qu'un seul numéro. Ce bug existe déjà aujourd'hui, puisque la double affectation est possible. Non testé. **Décision (Clarifications 2026-09-24)** : `Picker.bucketNumber` devient `bucketNumbers`, une liste de numéros (vide si aucun seau), sur la liste et le détail des cueilleurs. Aucune page front ne lit ce champ aujourd'hui (`bucketNumber` absent de `front/*.html`), donc seuls l'API et `PickerService` changent. **Résolu (006)** : `PickerService` regroupe les numéros par cueilleur (FR-006) ; la liste et le détail renvoient `200`.
- **Désaffectation** (écart corrigé) : à l'origine aucune route ne permettait de retirer un cueilleur d'un seau, seule une réaffectation via le même `PUT` était possible ; combiné à la spec `001` (suppression refusée tant qu'il a un seau), un cueilleur ayant un seau ne pouvait jamais être supprimé. **Résolu (2026-09-24)** : ajouter `DELETE /api/buckets/{bucketId}/picker`, qui retire le cueilleur du seau (`204`, ou `404` si le seau n'existe pas). **Livré par la feature `001`** (voir FR-007).
- **Aucune interface utilisateur** : `doc/20251116-use_cases.md:82-100` ("Affecter des seaux") décrit un parcours UI complet (page de gestion des seaux, sélection, liste des cueilleurs, fenêtre de confirmation). Aucune page de `/front` n'appelle `/api/buckets*` (vérifié par recherche de `buckets` dans `front/*.html` — la seule occurrence, `front/index.html:293`, est une variable locale de graphique temporel sans rapport avec l'entité métier "seau"). **Résolu (2026-09-24, révisé lors de `/speckit-plan`)** : il n'y a pas d'outil externe — l'affectation des seaux fait partie du front web, utilisée par un Administrateur connecté. Une page `/front` est à construire, conformément au parcours de `doc/20251116-use_cases.md:82-100`. **Livré (006)** : `front/buckets.html`.
- **Authentification** (écart corrigé) : à l'origine aucune authentification sur ces routes, malgré la précondition "L'utilisateur est connecté" de `doc/`. **Livré par la spec `008`** : `/api/buckets/**` exige un utilisateur connecté de rôle Administrateur (`SecurityConfig.java:125`) ; l'Opérateur reçoit `403`, un appelant anonyme `401` (vérifié par `AccessMatrixSecurityTest.java:74-78`).
- **Suppression d'un seau en cours de lecture** (Clarifications 2026-10-11) : si un tag du seau est lu au moment où le seau est supprimé, l'une des deux opérations passe avant l'autre — soit le tag est compté avec le cueilleur du seau puis le seau est supprimé, soit le seau est supprimé puis le tag est compté sans cueilleur. Aucune des deux ne doit échouer.
- **Seau supprimé pendant une session d'enregistrement** : une session ouverte vise un numéro de seau, pas un seau existant ; l'enregistrer après la suppression recrée un seau de ce numéro (scénario 6 de la User Story 4).
- **Aucun test** pour `BucketService`/`BucketController`. **Résolu (Clarifications 2026-09-25)** : tests backend requis, voir SC-002.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT lister tous les seaux, triés par numéro croissant, avec leurs tags associés et leur cueilleur éventuel. Evidence: `BucketService.java:35-67`.
- **FR-002**: Le système DOIT permettre de consulter le détail d'un seau par id, avec `404` si absent. Evidence: `BucketService.java:69-87`.
- **FR-003**: Le système DOIT permettre d'affecter un seau existant à un cueilleur existant, avec `404` si l'un des deux est absent. Evidence: `BucketService.java:89-98`.
- **FR-004**: Le système NE vérifie PAS qu'un cueilleur n'a pas déjà un seau avant de lui en affecter un nouveau, et NE désaffecte PAS automatiquement un éventuel seau précédent. Evidence: `BucketService.java:89-98`. **Décision (Clarifications 2026-09-24)** : confirmé comme comportement cible — un cueilleur peut avoir plusieurs seaux ; `doc/20251013-database_diagram.puml:66` est obsolète sur ce point.
- **FR-004a** (Clarifications 2026-09-25) : la réaffectation d'un seau déjà affecté à un autre cueilleur DOIT rester possible (l'API écrase l'affectation, `204`, pas de `409`) ; la page front DOIT, dans la fenêtre de confirmation, nommer le cueilleur actuellement affecté avant de valider.
- **FR-005**: Le système DOIT réserver toutes les routes `/api/buckets/**` à un utilisateur connecté de rôle Administrateur (`401` anonyme, `403` Opérateur). **Livré par la spec `008`** (`SecurityConfig.java:125`, `AccessMatrixSecurityTest.java:74-78`).
- **FR-006** (nouveau, Clarifications 2026-09-24) : l'API cueilleurs DOIT exposer tous les seaux d'un cueilleur sous forme de liste `bucketNumbers` (vide si aucun), et la liste comme le détail des cueilleurs DOIVENT fonctionner quand un cueilleur a plusieurs seaux. Non respecté aujourd'hui (`PickerService.java:45-47,88`, `api.yaml:476-479`). **Livré (006)** : `Picker.bucketNumbers` (`api.yaml`), `PickerService`, testé par `PickerServiceTest` et `BucketApiTest.pickerWithTwoBuckets_listAndDetailReturn200WithBothNumbers`.
- **FR-007** (nouveau, Clarifications 2026-09-24) : le système DOIT permettre de retirer le cueilleur d'un seau via `DELETE /api/buckets/{bucketId}/picker` (`204`, ou `404` si le seau n'existe pas). **Livré par la feature `001`** (tâches T001, T021, T022) : `BucketService.unassignBucketFromPicker`, idempotent (`204` aussi si le seau n'avait pas de cueilleur), Administrateur seul.
- **FR-008** (Clarifications 2026-09-25) : la page front de gestion des seaux DOIT proposer, pour chaque seau affecté, une action "Désaffecter" ; après une fenêtre de confirmation nommant le cueilleur actuel, elle appelle `DELETE /api/buckets/{bucketId}/picker` (FR-007) et rafraîchit la liste (le seau apparaît sans cueilleur).
- **FR-009** (Clarifications 2026-09-25) : pour choisir le cueilleur, la page DOIT proposer une liste déroulante filtrable par saisie, contenant **tous** les cueilleurs triés par nom ; elle charge toutes les pages de `GET /api/pickers` (`size` ≤ 100, `api.yaml:755-763`) jusqu'à la dernière. Aucune modification de l'API cueilleurs n'est requise.
- **FR-010** (nouveau, Clarifications 2026-10-11) : le système DOIT permettre à un Administrateur (et à lui seul, FR-005) de supprimer un seau, qu'il ait ou non des tags et un cueilleur. La suppression est définitive : le seau disparaît de la liste et du détail des seaux et des numéros de seaux de son cueilleur, et son numéro redevient libre (un enregistrement de tags sous ce numéro crée un nouveau seau). Ses tags restent connus mais sans seau. Les lectures passées sont conservées avec leur tag, leur cueilleur, leur activité et leur conformité ; les totaux du tableau de bord et les heures de travail ne changent pas. Un seau inconnu ou déjà supprimé DOIT répondre `404`. La page "Seaux" DOIT proposer une action "Supprimer" sur chaque seau, avec une fenêtre de confirmation qui donne le numéro du seau, le nombre de tags libérés (s'il y en a) et le cueilleur affecté (s'il y en a), puis rafraîchir la liste. **Livré** : `DELETE /api/buckets/{bucketId}`, `BucketService.deleteBucket`, bouton « Supprimer » de `front/buckets.html`.

### Key Entities

- **Bucket** (`bucket` table) : voir spec `003`. Relation `picker` non contrainte en unicité côté base/JPA. Cible (Clarifications 2026-09-24) : relation 1 cueilleur → 0..n seaux, un seau ayant 0..1 cueilleur. Cible (Clarifications 2026-10-11, FR-010) : un seau peut être supprimé définitivement ; ses tags perdent leur seau, et les lectures qui le désignaient le perdent aussi, sans autre changement.
- **Picker** : voir spec `001`.
- **Tag** : voir spec `003` ; un tag sans seau est un état normal (jamais enregistré, ou déjà compté, spec `004`), que la suppression d'un seau donne aussi.
- **Record** : voir spec `004` ; il garde son cueilleur et son activité, copiés à la lecture, indépendamment du seau.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (observé) : une affectation réussie renvoie `204` et le `GET` suivant sur le seau reflète le nouveau cueilleur.
- **SC-002** (Clarifications 2026-09-25) : des tests backend automatisés couvrent, et passent : (a) tests unitaires `BucketService` — liste triée par numéro, détail, affectation, réaffectation (l'ancien cueilleur perd le seau), désaffectation, `404` seau/cueilleur absent ; (b) tests contrôleur/sécurité — mêmes routes, `401` non connecté, `403` Opérateur, `2xx` Administrateur ; (c) un cueilleur affecté à 2 seaux → `GET /api/pickers` et `GET /api/pickers/{id}` renvoient `200` avec `bucketNumbers` de 2 éléments. La page front est vérifiée manuellement (pas de tests navigateur).
- **SC-003** (Clarifications 2026-10-11, livré) : un Administrateur supprime un seau en 2 clics ("Supprimer", puis confirmer) depuis la page "Seaux". Des tests backend automatisés couvrent, et passent : suppression d'un seau vide, d'un seau avec tags et cueilleur (tags sans seau, cueilleur sans ce numéro), lectures passées et totaux du tableau de bord inchangés, numéro réutilisable, `404` seau inconnu ou déjà supprimé, `401` non connecté, `403` Opérateur.

## Assumptions

- "Seau" (doc/front) et "Bucket" (code) désignent la même entité.
- **Révisé lors de `/speckit-plan`** : ce module est utilisé depuis le front web par un Administrateur connecté ; une page `/front` est prévue.
- (2026-10-11) La suppression d'un seau est physique, pas logique comme celle d'un lecteur (spec `002`, FR-008) : un seau n'a pas d'historique propre à conserver (les lectures portent le cueilleur), et un numéro gardé pris bloquerait l'enregistrement de tags sous ce numéro, qui crée le seau à la volée.
- (2026-10-11) Pas de suppression en lot : un seau à la fois.
- (2026-10-11) La suppression d'un seau est réservée à l'Administrateur comme toutes les routes des seaux ; la ligne `/api/buckets/**` de la table d'accès de la spec `008` la couvre déjà.

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| `picker ||--o| bucket` — un cueilleur a au plus un seau (`doc/20251013-database_diagram.puml:66`) | Aucune contrainte d'unicité ; un cueilleur peut être affecté à plusieurs seaux. Confirmé comme cible (Clarifications 2026-09-24) — diagramme BDD corrigé en 1 → 0..n (006) | `BucketEntity.java:36-38`, `BucketService.java:89-98` |
| Parcours UI complet pour l'affectation (`doc/20251116-use_cases.md:82-100`) | Conforme depuis 006 : page `front/buckets.html` pour un Administrateur connecté (affecter, réaffecter, désaffecter) | `front/buckets.html` |
| Précondition "L'utilisateur est connecté" | Conforme depuis la spec `008` : rôle Administrateur requis | `SecurityConfig.java:125` |
