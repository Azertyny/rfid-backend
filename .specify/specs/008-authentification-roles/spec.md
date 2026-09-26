# Feature Specification: Authentification et rôles

**Feature Branch**: `008-authentification-roles` (spec consolidée ; le travail se fait aujourd'hui sur `dev`)

**Created**: 2026-09-24

**Status**: Draft — cible, pas as-is

**Input**: User description: "/speckit-plan on shared auth and roles work" — consolide les décisions d'authentification et de rôles prises lors des clarifications des specs `001`, `002`, `005`, `006` et `007` (session 2026-09-24), plus deux décisions prises au lancement du plan.

> Aujourd'hui, seule `POST /api/tags/scan` est protégée (jeton de lecteur, `security/ReaderApiTokenAuthenticationFilter.java`). Toutes les autres routes sont `permitAll()` (`configuration/SecurityConfig.java:32-36`) et il n'existe aucune notion d'utilisateur. Cette spec décrit le comportement cible.

## Clarifications

### Session 2026-09-24

- Q: How should user accounts (Administrateur, Opérateur) be created? → A: Admin manages users — the first Administrateur is created at startup from environment variables; Administrateurs then create, deactivate and reset users through the API. No self sign-up.
- Q: How should the external tool for tag registration (003) and bucket assignment (006) authenticate? → A: There is no external tool — both features belong to the web front, used by a logged-in Administrateur at a station equipped with a reader. Every human user therefore logs in through the web front; reader tokens remain the only machine credential.

### Session 2026-09-25

- Q: How should an Opérateur using the line's kiosk touch screen (`reader.html` on the kiosk computer, full screen, browser not reachable, physically protected) be identified without typing a login and password? → A: The kiosk page authenticates with the line reader's existing token. The kiosk launch script reads it from the reader's config file on the kiosk and opens `reader.html#token=…` (URL fragment, never sent to the server or logged); the page keeps it for the tab's lifetime (refined during `/speckit-plan`: `sessionStorage`, research R15), sends it as `x-api-token`, and removes it from the address bar. The token only gives access to that reader's records; conformity changes made this way are credited to the reader, not to a person.

Décisions reprises des autres specs (non reposées) :
- Il faut une vraie authentification ; son absence est un écart à corriger (`001`, `002`, `003`, `006`).
- Deux rôles distincts : Administrateur (tout) et Opérateur (consultation des lectures, bascule de conformité, tableau de bord) (`005`).
- Les routes `/records` exigent une connexion utilisateur, pas un jeton de lecteur (`005`). **Révisé (Clarifications 2026-09-25)** : `GET /api/records/readers/{readerId}` et `PATCH /api/records/{recordId}/conformity` acceptent aussi le jeton d'un lecteur, pour ses propres lectures et `Record`s uniquement (kiosque tactile de la ligne, FR-005a).
- `GET /readers` est ouvert aux deux rôles ; `apitoken` n'est renvoyé qu'aux Administrateurs (`002`, `007`).
- Le tableau de bord est accessible aux deux rôles, en lecture seule (`007`).
- Le jeton codé en dur dans `front/index.html` doit être retiré (`007`).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Se connecter et se déconnecter (Priority: P1)

Un utilisateur (Administrateur ou Opérateur) ouvre une page du front, est redirigé vers une page de connexion s'il n'est pas connecté, saisit identifiant et mot de passe, puis revient sur la page demandée. Il peut se déconnecter.

**Why this priority**: sans connexion, aucune autre règle de rôle n'a de sens.

**Independent Test**: se connecter avec le premier Administrateur créé au démarrage, appeler `GET /api/auth/me`, se déconnecter, vérifier que `GET /api/auth/me` renvoie `401`.

**Acceptance Scenarios**:

1. **Given** un compte actif, **When** l'utilisateur envoie de bons identifiants, **Then** une session est ouverte et `GET /api/auth/me` renvoie son identifiant et son rôle.
2. **Given** de mauvais identifiants ou un compte désactivé, **When** l'utilisateur tente de se connecter, **Then** `401`, sans indiquer lequel des deux champs est faux.
3. **Given** un utilisateur connecté, **When** il se déconnecte, **Then** la session est invalidée et les appels suivants renvoient `401`.
4. **Given** aucun utilisateur connecté, **When** une page du front est ouverte, **Then** elle redirige vers la page de connexion.

---

### User Story 2 - Accès selon le rôle (Priority: P1)

Chaque route de l'API n'est accessible qu'aux rôles prévus par la matrice ci-dessous. Un Opérateur peut consulter les lectures, basculer la conformité et voir le tableau de bord, mais ne peut rien gérer.

**Why this priority**: c'est ce qui ferme la fuite des jetons de lecteur et les modifications anonymes constatées dans les specs `001` à `007`.

**Independent Test**: pour chaque route et chaque profil (anonyme, Opérateur, Administrateur, jeton de lecteur du kiosque), vérifier le code HTTP attendu. Le jeton de lecteur n'ouvre que les deux routes du kiosque, pour son propre lecteur (`403` partout ailleurs).

**Acceptance Scenarios**:

1. **Given** un appel anonyme, **When** il vise une route protégée, **Then** `401`.
2. **Given** un Opérateur connecté, **When** il appelle une route réservée à l'Administrateur (ex. `POST /api/pickers`), **Then** `403`.
3. **Given** un Opérateur connecté, **When** il appelle `GET /api/readers`, **Then** `200` sans `apitoken` dans la réponse.
4. **Given** un Administrateur connecté, **When** il appelle `GET /api/readers`, **Then** `200` avec `apitoken`.
5. **Given** un lecteur avec un jeton valide, **When** il appelle `POST /api/tags/scan`, **Then** le comportement actuel est inchangé (pas de session, pas de connexion utilisateur).
6. **Given** le kiosque de la ligne L ouvert avec le jeton du lecteur L, **When** il appelle `GET /api/records/readers/{L}` ou `PATCH /api/records/{id}/conformity` pour un `Record` du lecteur L, **Then** `200` / `204`, sans session ni jeton CSRF ; la modification de conformité est attribuée au lecteur L.
7. **Given** le jeton du lecteur L, **When** il vise les lectures ou un `Record` d'un autre lecteur, **Then** `403` ; **When** il vise toute autre route (gestion, tableau de bord, `GET /api/readers`…), **Then** `403`.

---

### User Story 3 - Gérer les comptes (Priority: P2)

Un Administrateur crée des comptes (identifiant, mot de passe initial, rôle), change le rôle d'un compte, le désactive ou le réactive, et réinitialise son mot de passe.

**Why this priority**: nécessaire pour créer les Opérateurs, mais un seul Administrateur (créé au démarrage) suffit pour démarrer.

**Independent Test**: en tant qu'Administrateur, créer un Opérateur, se connecter avec lui, le désactiver, vérifier que la connexion échoue.

**Acceptance Scenarios**:

1. **Given** aucun Administrateur actif en base, **When** l'application démarre avec les variables d'environnement du premier Administrateur, **Then** ce compte est créé.
2. **Given** au moins un Administrateur actif, **When** l'application redémarre, **Then** aucun compte n'est recréé ni modifié.
3. **Given** un identifiant déjà utilisé, **When** un Administrateur crée un compte, **Then** `409`.
4. **Given** un compte désactivé, **When** son titulaire tente de se connecter, **Then** `401` ; ses sessions ouvertes cessent d'être acceptées.
5. **Given** un Administrateur qui est le dernier Administrateur actif, **When** on tente de le désactiver ou de lui retirer le rôle Administrateur, **Then** `409`.
6. **Given** un compte connecté, **When** un Administrateur change son rôle ou réinitialise son mot de passe, **Then** toutes les sessions ouvertes de ce compte sont fermées (appel suivant → `401`), y compris lorsque l'Administrateur modifie son propre compte.

### Edge Cases

- Démarrage sans variables d'environnement du premier Administrateur et sans Administrateur en base : l'application démarre mais journalise un avertissement clair (personne ne peut se connecter).
- Démarrage sans Administrateur actif alors que l'identifiant du premier Administrateur est déjà pris par un autre compte (désactivé ou Opérateur) : aucun compte n'est créé ni modifié, l'application démarre et journalise une erreur, plutôt que d'échouer sur la contrainte d'unicité.
- Session expirée pendant qu'une page est ouverte : l'appel suivant renvoie `401` et la page redirige vers la connexion.
- Les écrans qui interrogent l'API en boucle (`reader.html` toutes les 500 ms, `index.html` toutes les 3 s) maintiennent la session active tant qu'ils sont ouverts.
- Kiosque dont le lecteur est désactivé ou dont le jeton a été changé : ses appels renvoient `401` ; `reader.html` affiche un message demandant l'intervention d'un Administrateur au lieu de rediriger vers la page de connexion. Après une rotation, il suffit de mettre à jour le fichier de configuration du lecteur sur le kiosque et de relancer celui-ci.
- Un compte n'est jamais supprimé, seulement désactivé : l'historique des modifications de conformité (spec `005`) référence son auteur.
- Transport : la production est servie en HTTPS par Caddy (spec `009`) ; un appel `/api/*` en HTTP simple est refusé (`403`, spec `009` FR-009a) au lieu d'être redirigé. Identifiants, cookie de session et jeton de lecteur (lecteurs et kiosque, envoyé à chaque requête) ne circulent donc jamais en clair. Les journaux d'accès de Caddy sont désactivés (pas de directive `log`) ; s'ils sont activés un jour, l'en-tête `x-api-token` doit en être exclu (SC-006).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT authentifier les utilisateurs par identifiant et mot de passe, et maintenir une session côté serveur.
- **FR-002**: Le système DOIT stocker les mots de passe sous forme hachée, jamais en clair.
- **FR-003**: Le système DOIT connaître deux rôles : `ADMINISTRATEUR` et `OPERATEUR`. Un compte a exactement un rôle.
- **FR-004**: Le système DOIT appliquer la matrice d'accès ci-dessous ; `401` pour un appel anonyme sur une route protégée, `403` pour un rôle insuffisant. Exception : un appel en écriture (`POST`, `PUT`, `PATCH`, `DELETE`) sans jeton CSRF reçoit `403` avant toute vérification d'authentification, anonyme ou non (le contrôle CSRF s'exécute en premier).
- **FR-005**: Le système DOIT continuer d'authentifier `POST /api/tags/scan` par jeton de lecteur (`x-api-token`), sans session ni compte utilisateur.
- **FR-005a**: Le système DOIT aussi accepter le jeton de lecteur (`x-api-token`, sans session ni CSRF) sur `GET /api/records/readers/{readerId}` et `PATCH /api/records/{recordId}/conformity`, uniquement pour les lectures et `Record`s de **ce** lecteur (`403` sinon). C'est le mode d'accès du kiosque tactile de la ligne (Clarifications 2026-09-25). Une modification de conformité faite avec ce jeton a pour auteur le lecteur, pas un Utilisateur. Le jeton ne donne accès à aucune autre route. **Étendu par la spec `012`** : le jeton du kiosque ouvre aussi `GET/PUT /api/lines/{uid}/current-activity` pour sa propre ligne (`403` pour une autre).
- **FR-005b**: En mode kiosque, `front/reader.html` DOIT lire le nom du lecteur et le jeton dans le fragment d'URL (`#reader=…&token=…`), le garder pour la durée de l'onglet (`sessionStorage`, jamais `localStorage`), l'effacer de la barre d'adresse (`history.replaceState`) et l'envoyer en `x-api-token`. Aucun jeton n'est écrit dans les fichiers de `front/` (SC-003). Le lanceur du kiosque, qui lit le jeton dans le fichier de configuration du lecteur, relève du déploiement du kiosque.
- **FR-006**: Le système DOIT omettre `apitoken` des réponses de `GET /api/readers` pour un Opérateur.
- **FR-007**: Le système DOIT créer le premier Administrateur au démarrage à partir de variables d'environnement, uniquement s'il n'existe aucun Administrateur actif.
- **FR-008**: Un Administrateur DOIT pouvoir lister, créer, désactiver, réactiver des comptes, changer leur rôle et réinitialiser leur mot de passe. Aucune inscription libre.
- **FR-009**: Le système DOIT refuser (`409`) toute opération qui laisserait zéro Administrateur actif.
- **FR-010**: Le système DOIT exposer `GET /api/auth/me` (identifiant, rôle) pour que le front sache qui est connecté.
- **FR-011**: Le système DOIT protéger les requêtes qui modifient l'état contre la falsification de requête inter-sites (CSRF), puisque l'authentification repose sur un cookie.
- **FR-012**: Le front DOIT fournir une page de connexion, rediriger vers elle sur `401` (sauf en mode kiosque, FR-005b), et ne plus contenir de jeton de lecteur dans ses fichiers (retrait du jeton codé en dur de `front/index.html:155`) ; seul `reader.html` en mode kiosque envoie un jeton, reçu à l'exécution par le fragment d'URL.

#### Matrice d'accès cible

| Route | Anonyme | Opérateur | Administrateur | Source |
|---|---|---|---|---|
| `POST /api/auth/login` | ✓ | ✓ | ✓ | FR-001 |
| `POST /api/auth/logout`, `GET /api/auth/me` | — | ✓ | ✓ | FR-010 |
| `POST /api/tags/scan` | jeton de lecteur uniquement | — | — | FR-005, spec `004` |
| `GET /api/pickers`, `GET /api/pickers/{id}` | — | ✓ | ✓ | spec `007` (noms sur le tableau de bord) |
| `POST/PUT/DELETE /api/pickers/**` | — | — | ✓ | spec `001` |
| `GET /api/readers` | — | ✓ sans `apitoken` | ✓ avec `apitoken` | specs `002`, `007` |
| `POST /api/readers`, `PATCH /api/readers/{id}` (désactivation), `POST /api/readers/{id}/token` (rotation), et future route de changement de mode | — | — | ✓ | specs `002`, `003` |
| `POST /api/tags/buckets/{bucketNumber}` | — | — | ✓ | spec `003` |
| Futures routes des lectures temporaires d'enregistrement (démarrer, consulter, effacer) | — | — | ✓ | spec `003` |
| `/api/buckets/**` | — | — | ✓ | spec `006` |
| `GET /api/records/{id}/conformity-history` | — | — | ✓ | spec `005` (FR-007), règle placée avant `/api/records/**` |
| `GET /api/records/readers/{readerId}`, `PATCH /api/records/{recordId}/conformity` | jeton de lecteur : uniquement ce lecteur et ses `Record`s (kiosque) | ✓ | ✓ | spec `005`, FR-005a |
| `/api/records/**` | — | ✓ | ✓ | spec `005` |
| `/api/users/**` | — | — | ✓ | FR-008 |
| `/actuator/health` | ✓ | ✓ | ✓ | supervision |
| `/h2-console/**` (profil dev) | ✓ | ✓ | ✓ | inchangé, dev uniquement |

### Key Entities

- **Utilisateur** : identifiant (unique, insensible à la casse), mot de passe haché, rôle (`ADMINISTRATEUR` ou `OPERATEUR`), actif (oui/non), date de création. Jamais supprimé. Référencé comme auteur par l'historique de conformité (spec `005`).
- **Lecteur** (spec `002`) : reste authentifié par son jeton, sans lien avec un Utilisateur. Son jeton sert aussi au kiosque tactile de sa ligne (FR-005a). Il peut donc être l'auteur d'une modification de conformité : l'auteur d'une entrée de l'historique (spec `005`) est soit un Utilisateur, soit un Lecteur.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % des routes de la matrice **qui existent à la livraison de cette fonctionnalité** renvoient le code attendu pour chacun des quatre profils (anonyme, Opérateur, Administrateur, jeton de lecteur du kiosque), vérifié par des tests automatisés (`AccessMatrixSecurityTest` ; routes du kiosque avec de vraies lectures : `KioskReaderTokenSecurityTest`). Les lignes décrivant des routes futures (rotation et mode des lecteurs, lectures temporaires d'enregistrement, spec `003`) fixent seulement la règle de préfixe ; elles seront testées avec leur propre fonctionnalité.
- **SC-002**: Aucun `apitoken` de lecteur n'apparaît dans une réponse destinée à un appelant anonyme ou à un Opérateur.
- **SC-003**: Aucun jeton ni mot de passe n'apparaît dans les fichiers de `front/`.
- **SC-004**: Un lecteur existant continue d'envoyer ses scans sans aucune modification de sa configuration.
- **SC-006**: Un Opérateur au kiosque tactile d'une ligne consulte les lectures et modifie la conformité sans saisir ni identifiant ni mot de passe ; le jeton n'apparaît ni dans les fichiers de `front/` ni dans les journaux d'accès du serveur.
- **SC-005**: Un nouvel utilisateur peut se connecter et atteindre son écran en moins d'une minute, sans aide.

## Assumptions

- Une seule instance de l'application (`deploy/docker-compose.yml`) : les sessions en mémoire suffisent, pas de session partagée.
- Le front et l'API sont servis sur la même origine par Caddy (`deploy/web/Caddyfile`, spec `009` ; `nginx` à la conception de cette spec) : un cookie de session fonctionne sans configuration CORS particulière.
- HTTPS est assuré par Caddy depuis la spec `009` (auparavant : HTTP sur le réseau local, risque accepté). La confidentialité du jeton du kiosque (FR-005a) repose sur ce HTTPS.
- Les pages de gestion à créer ou compléter (enregistrement des tags `003`, affectation des seaux `006`, gestion des utilisateurs) appliqueront cette authentification, mais leur contenu fonctionnel relève de leurs propres specs, sauf la page de gestion des utilisateurs, minimale, incluse ici.
- Hors périmètre : rotation/révocation des jetons de lecteur (`002`), source de données du tableau de bord (`007`). L'historique des modifications de conformité est livré par la spec `005`.
