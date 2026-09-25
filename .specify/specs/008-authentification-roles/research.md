# Research: Authentification et rôles

**Spec**: [spec.md](spec.md) | **Date**: 2026-09-24

Versions constatées : Spring Boot 3.5.7 (`pom.xml`), Spring Security 6.5.6 (dépôt Maven local), Java 21. Tous les points de la spec sont tranchés ; les choix ci-dessous sont techniques.

## R1. Mécanisme d'authentification : session serveur + cookie

- **Decision**: session HTTP côté serveur (cookie `JSESSIONID`, `HttpOnly`, `SameSite=Lax`), ouverte par un endpoint JSON `POST /api/auth/login`.
- **Rationale**: le front et l'API partagent la même origine via `nginx` (`deploy/front/default.conf`), donc le cookie est envoyé automatiquement par `fetch`, sans CORS. Une seule instance (`deploy/docker-compose.yml`), donc sessions en mémoire. La déconnexion et la désactivation d'un compte prennent effet immédiatement (voir R6), ce qu'un jeton autoportant ne permet pas sans liste de révocation.
- **Alternatives considered**: JWT porteur (stocké en JS, exposé au XSS, révocation complexe, aucun besoin multi-instance) ; HTTP Basic (identifiants renvoyés à chaque requête, pas de vraie déconnexion) ; `formLogin` Spring (formulaire `x-www-form-urlencoded` hors contrat OpenAPI, redirections HTML inadaptées à une API).

## R2. Endpoint de connexion dans une API « API-first »

- **Decision**: déclarer `/auth/login`, `/auth/logout`, `/auth/me` dans `src/main/resources/openapi/api.yaml` (tag `Auth`) et les implémenter dans un `AuthController implements AuthApiDelegate`, comme les autres contrôleurs. Le login appelle l'`AuthenticationManager`, puis enregistre le `SecurityContext` via `HttpSessionSecurityContextRepository` (enregistrement explicite obligatoire depuis Spring Security 6). Le logout délègue à `SecurityContextLogoutHandler`. Le login DOIT aussi appeler explicitement la `SessionAuthenticationStrategy` configurée, que `formLogin` appliquerait automatiquement mais pas un endpoint maison : changement d'identifiant de session (protection contre la fixation de session), enregistrement dans le `SessionRegistry` (nécessaire pour R6) et renouvellement du jeton CSRF.
- **Rationale**: respecte le principe du dépôt (le contrat est la source de vérité, `CLAUDE.md`) et garde un JSON cohérent (`401` sans redirection).
- **Alternatives considered**: `formLogin().loginProcessingUrl(...)` — hors contrat, réponse par redirection.

## R3. Deux chaînes de filtres

- **Decision**: deux beans `SecurityFilterChain` ordonnés :
  1. `@Order(1)`, `securityMatcher("/api/tags/scan")` : sans état, `ReaderApiTokenAuthenticationFilter`, CSRF désactivé, `authenticated()`.
  2. `@Order(2)` : tout le reste, session utilisateur, CSRF actif, matrice d'accès de la spec en `requestMatchers(HttpMethod, path).hasRole(...)`.
- **Rationale**: le lecteur ne doit jamais créer de session ni dépendre du CSRF (SC-004) ; la matrice des utilisateurs reste lisible en un seul endroit, qui reflète le tableau de la spec.
- **Point constaté**: `ReaderApiTokenAuthenticationFilter` est un `@Component` ; Spring Boot l'enregistre donc aussi comme filtre servlet global, en plus de son ajout dans la chaîne. Il faut désactiver cet enregistrement automatique (`FilterRegistrationBean` avec `setEnabled(false)`) ou ne plus en faire un `@Component`, sinon il s'exécute hors de la chaîne dédiée.
- **Alternatives considered**: une seule chaîne avec `SessionCreationPolicy.IF_REQUIRED` partout — le lecteur pourrait se voir attribuer une session et être soumis au CSRF.

## R4. Protection CSRF pour des pages en JavaScript simple

- **Decision**: `CookieCsrfTokenRepository.withHttpOnlyFalse()` + `CsrfTokenRequestAttributeHandler` (sans masquage XOR) : le serveur pose un cookie `XSRF-TOKEN`, le front le recopie dans l'en-tête `X-XSRF-TOKEN` sur `POST/PUT/PATCH/DELETE`. Un petit filtre force le chargement du jeton pour que le cookie existe dès la première réponse (les jetons sont différés depuis Spring Security 6). `POST /api/auth/login` est exempté (pas encore de session à protéger).
- **Rationale**: l'API passe d'aucune authentification à une authentification par cookie ; sans CSRF, une page tierce ouverte sur le réseau de la ligne pourrait déclencher des modifications au nom de l'utilisateur. Le raccourci `csrf.spa()` n'existe pas dans la version utilisée (vérifié dans `spring-security-config-6.5.6`), d'où la configuration manuelle documentée.
- **Alternatives considered**: CSRF désactivé en comptant sur `SameSite=Lax` seul — protection partielle (ne couvre pas un autre service du même site) ; jeton XOR par défaut — illisible tel quel depuis le cookie pour un front sans framework.

## R5. Stockage des comptes et des mots de passe

- **Decision**: nouvelle entité JPA `UserEntity`, table `app_user` (`user` est réservé en PostgreSQL). Mots de passe via `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (bcrypt par défaut, format `{bcrypt}...` évolutif). `UserDetailsService` lisant cette table ; rôle exposé comme autorité `ROLE_ADMINISTRATEUR` / `ROLE_OPERATEUR`. Identifiant unique insensible à la casse (normalisé en minuscules à l'écriture).
- **Rationale**: dépendances déjà présentes (`spring-boot-starter-security`, `spring-boot-starter-data-jpa`) ; `ddl-auto: update` créera la table (pas de migrations dans le projet, `application*.yml`).
- **Alternatives considered**: `InMemoryUserDetailsManager` — incompatible avec la gestion des comptes par l'API ; `JdbcUserDetailsManager` — schéma imposé, peu cohérent avec le reste en JPA.

## R6. Désactivation effective et sessions ouvertes

- **Decision**: `SessionRegistryImpl` + `HttpSessionEventPublisher`, `sessionManagement().maximumSessions(-1).sessionRegistry(...)`. À la désactivation ou au changement de rôle d'un compte, le service expire toutes ses sessions (`getAllSessions(principal, false)` puis `expireNow()`).
- **Rationale**: la spec exige qu'un compte désactivé perde l'accès immédiatement ; un changement de rôle doit aussi s'appliquer sans attendre la fin de session.
- **Alternatives considered**: recharger l'utilisateur à chaque requête — requête base à chaque appel, alors que `reader.html` interroge l'API toutes les 500 ms.

## R7. Premier Administrateur

- **Decision**: un `ApplicationRunner` lit `app.security.bootstrap-admin.username` / `.password` (variables `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD`, ajoutées à `deploy/.env.example`, `deploy/.env.local.example` et `deploy/docker-compose.yml`). S'il n'existe aucun Administrateur actif et que les deux valeurs sont définies, il crée le compte ; sinon il ne fait rien (avertissement journalisé s'il n'y a aucun Administrateur). Si l'identifiant configuré appartient déjà à un autre compte, il ne crée rien et journalise une erreur, pour ne pas faire échouer le démarrage sur la contrainte d'unicité.
- **Rationale**: idempotent (redémarrages sans effet, scénario US3-2), aucun secret dans le code ni dans `front/`.

## R8. Masquer `apitoken` aux Opérateurs

- **Decision**: rendre `apitoken` optionnel dans le schéma `Reader` de `api.yaml` (retiré de `required`) ; `ReaderService.getReaders` ne le renseigne que si l'appelant a le rôle Administrateur. `front/reader.html` ne doit plus exiger `apitoken` pour sélectionner un lecteur (`front/reader.html:156-159` bloque aujourd'hui la sélection sans jeton) ni l'envoyer sur `/records`.
- **Rationale**: une seule route (décision `002`/`007`), contrat honnête (le champ peut être absent).

## R9. Front sans framework

- **Decision**: ajouter `front/auth.js` (partagé, chargé après `config.js`) : fonction `apiFetch` qui ajoute `X-XSRF-TOKEN`, redirige vers `login.html` sur `401` et affiche un message sur `403` ; fonction `requireRole(...)` appelée au chargement de chaque page (via `GET /api/auth/me`). Nouvelles pages `front/login.html` et `front/users.html`. Retrait de `API_TOKEN` et des en-têtes `x-api-token` de `front/index.html` et `front/reader.html`.
- **Rationale**: les pages actuelles sont du HTML + JS en ligne (`front/*.html`) ; un fichier partagé évite de dupliquer la logique dans 6 pages.
- **Note**: les pages restent téléchargeables sans connexion (servies par `nginx`) ; la protection réelle est côté API. C'est acceptable : elles ne contiennent plus aucun secret (SC-003).

## R10. Tests

- **Decision**: tests `@SpringBootTest` + `@AutoConfigureMockMvc` avec `spring-security-test` (déjà dans `pom.xml`) : un test paramétré parcourt la matrice (route × profil anonyme/Opérateur/Administrateur → code attendu), plus des tests ciblés (login/logout, compte désactivé, dernier Administrateur, `apitoken` masqué, lecteur par jeton inchangé, CSRF manquant → `403`). Nouveau profil `test` (`src/test/resources/application-test.yml`, H2 en mémoire).
- **Rationale**: `RfidBackApplicationTests` utilise aujourd'hui le profil `dev`, dont la base H2 est un fichier du dépôt (`./data/rfidbackdb`) : de nouveaux tests écriraient dedans.

## Risques connus (acceptés pour cette itération)

- HTTP en clair sur le réseau local (`deploy/INSTALL.md`) : mot de passe et cookie lisibles par qui écoute ce réseau. À traiter côté déploiement (TLS sur `nginx`).
- Sessions en mémoire : un redémarrage de l'application déconnecte tout le monde.

---

# Amendement 2026-09-25 : kiosque tactile avec le jeton du lecteur

Décision de la spec (Clarifications 2026-09-25, FR-005a, FR-005b ; spec `005` FR-004) : le `reader.html` du kiosque d'une
ligne s'authentifie avec le jeton de son lecteur, reçu par le fragment d'URL. Choix techniques ci-dessous.

## R11. Troisième chaîne de filtres : « toute requête portant `x-api-token` est une requête de lecteur »

- **Decision**: une nouvelle `SecurityFilterChain` `@Order(2)` (la chaîne utilisateur passe en `@Order(3)`), sans état, CSRF
  désactivé, `ReaderApiTokenAuthenticationFilter`. Son `securityMatcher` : chemin `/api/**` **et** en-tête `x-api-token`
  présent (`RequestHeaderRequestMatcher`). Règles : `GET /api/records/readers/*` et `PATCH /api/records/*/conformity` →
  `authenticated()` ; `OPTIONS` → `permitAll()` ; tout le reste → `denyAll()`. Jeton absent de la base ou lecteur
  désactivé → `401` (le filtre, comme aujourd'hui) ; jeton valide sur une autre route → `403` (spec, US2 scénario 7).
  `POST /api/tags/scan` reste servie par la chaîne `@Order(1)`, inchangée.
- **Rationale**: la chaîne utilisateur et sa matrice ne changent pas ; une requête sans l'en-tête suit exactement le
  même chemin qu'avant (Opérateur ou Administrateur connecté). Une requête avec l'en-tête ne touche jamais aux
  sessions ni au CSRF, ce qui est sûr : une page tierce ne peut pas faire envoyer un en-tête personnalisé par le
  navigateur sans CORS, contrairement à un cookie.
- **Point constaté**: `ReaderApiTokenAuthenticationFilter` teste lui-même le chemin (`protectedEndpoint`, `/api/tags/scan`
  seulement) et laisse passer toute autre requête sans l'authentifier. Ce test doit disparaître : ce sont désormais les
  `securityMatcher` des deux chaînes qui décident où le filtre s'applique.
- **Alternatives considered**: ajouter le filtre à la chaîne utilisateur et un rôle `LECTEUR` dans la matrice — mélange
  sessions et jeton, et le CSRF s'appliquerait au `PATCH` du kiosque ; limiter le `securityMatcher` aux deux routes —
  un jeton valide sur une autre route tomberait dans la chaîne utilisateur et recevrait `401` au lieu de `403`.

## R12. « Uniquement son propre lecteur » : contrôle dans `RecordService`

- **Decision**: `RecordService` lit l'authentification courante. Si c'est un `ReaderAuthentication` :
  `listLatestRecordsForReader(readerUid)` exige `readerUid` égal au `name` du lecteur authentifié ;
  `updateRecordConformity` exige `record.reader.id` égal à son `id`. Sinon `ResponseStatusException(FORBIDDEN)`, comme
  `RegistrationService` (spec `003`). Le contrôle du `PATCH` se fait après le chargement verrouillé du `Record` et avant
  toute écriture. Ordre des réponses : `Record` inexistant → `404`, `Record` d'un autre lecteur → `403`.
- **Rationale**: l'URL du `PATCH` ne dit pas à quel lecteur appartient le `Record` ; seule la couche service le sait.
  Le `404` avant `403` révèle seulement qu'un identifiant UUID existe, sans intérêt pour un attaquant.
- **Alternatives considered**: `@PreAuthorize` avec une expression — il faudrait charger le `Record` deux fois ;
  contrôle dans le contrôleur — la règle métier resterait hors de la couche testée par `RecordServiceTest`.

## R13. Auteur d'une modification : Utilisateur ou Lecteur

- **Decision**: `record_conformity_change.author_id` devient facultatif et une colonne `author_reader_id` (FK vers
  `reader`, facultative) est ajoutée. Invariant : exactement l'une des deux est renseignée, vérifié par l'entité
  (`@PrePersist`). Dans l'API, `ConformityChange` gagne `authorType` (`USER` | `READER`, obligatoire) et
  `authorReaderUid` ; `authorUsername` n'est plus obligatoire (renseigné seulement pour `USER`).
- **Rationale**: garde une seule table, donc l'historique trié (FR-007 de `005`) et le verrou « un `Record` modifié ne
  change plus sur relecture » (FR-006 de `005`, `TagService`) restent une seule requête d'existence, sans changement.
- **Alternatives considered**: un compte technique `app_user` par lecteur — la spec crédite le lecteur, pas un
  utilisateur, et il faudrait synchroniser deux entités ; une table séparée pour les modifications faites au kiosque —
  deux sources pour l'historique et pour le verrou de FR-006.

## R14. `ddl-auto: update` ne retire pas un `NOT NULL`

- **Decision**: Hibernate ajoute la colonne `author_reader_id` et sa clé étrangère, mais ne modifie jamais une colonne
  existante : `author_id` resterait `NOT NULL` en production (PostgreSQL) et dans la base H2 fichier de `dev`. Un
  `ApplicationRunner` dédié (`configuration/ConformityAuthorSchemaUpgrade`) exécute au démarrage
  `ALTER TABLE record_conformity_change ALTER COLUMN author_id DROP NOT NULL`, idempotent et accepté tel quel par
  PostgreSQL et H2 2.x. Il s'exécute après la mise à jour du schéma par Hibernate (création de l'`EntityManagerFactory`),
  journalise ce qu'il fait, et porte un commentaire : à supprimer quand un outil de migration sera introduit. Le test de
  bout en bout (profil `test`, H2 en mémoire, table créée par Hibernate déjà sans `NOT NULL`) vérifie seulement qu'il
  ne casse pas le démarrage ; un test dédié crée la colonne `NOT NULL` puis vérifie que le runner la rend facultative.
- **Rationale**: sans cela, le premier `PATCH` d'un kiosque échouerait en production (`500`, violation de contrainte).
  Une étape SQL manuelle dans `deploy/INSTALL.md` risque d'être oubliée ; `deploy.sh` sauvegarde déjà la base avant
  chaque déploiement.
- **Retour arrière**: une version antérieure fonctionne sur le schéma modifié (elle renseigne toujours `author_id`),
  mais sa lecture de l'historique échoue sur une ligne créée par un kiosque (`author_id` vide). Documenté dans la section
  Rollback de `deploy/INSTALL.md`.
- **Alternatives considered**: introduire Flyway maintenant — hors périmètre, demanderait une base de référence pour une
  base de production existante ; étape SQL manuelle — voir ci-dessus.

## R15. Remise du jeton au navigateur du kiosque

- **Decision**: le lanceur du kiosque ouvre `https://<hôte>/reader.html#reader=<nom du lecteur>&token=<jeton>`, en lisant le
  jeton dans le fichier de configuration du lecteur. `front/auth.js` lit le fragment au chargement, garde `reader` et
  `token` dans `sessionStorage`, puis efface le fragment (`history.replaceState`). En mode kiosque, `apiFetch` envoie
  `x-api-token`, n'envoie pas de cookie (`credentials: 'omit'`), ni d'en-tête CSRF ; sur `401` il affiche « Kiosque
  désactivé, contactez un administrateur » au lieu de rediriger vers `login.html`.
- **Rationale**: le fragment n'est jamais envoyé au serveur, donc jamais dans les journaux de Caddy ou du backend
  (SC-006). `sessionStorage` plutôt que la mémoire seule : un rechargement de l'onglet (plantage, rechargement
  automatique) ne perd pas le jeton alors que le fragment a été effacé ; il disparaît à la fermeture du navigateur, et
  le lanceur le redonne au démarrage suivant. Le nom du lecteur est passé aussi, parce que la route
  `GET /api/records/readers/{readerId}` l'attend et que le kiosque ne peut pas appeler `GET /api/readers` (R11).
- **Changement de spec**: FR-005b disait « en mémoire uniquement » ; précisé en « pour la durée de l'onglet
  (`sessionStorage`) ».
- **Alternatives considered**: `localStorage` — copie durable du jeton, à resynchroniser après une rotation ; paramètre
  de requête `?token=` — écrit dans les journaux d'accès ; route « quel lecteur suis-je ? » — une route de plus pour une
  information que le lanceur connaît.

## R16. Coût du contrôle du jeton à chaque appel

- **Decision**: rien à ajouter. Le filtre fait une recherche par `apitoken` (colonne `unique`, donc indexée) à chaque
  requête ; un kiosque interroge toutes les 500 ms, soit 2 recherches par seconde et par kiosque, à côté de la requête
  des 10 dernières lectures qu'il déclenche de toute façon.
- **Rationale**: R6 évitait la base à chaque appel pour les sessions utilisateur ; ici la recherche par clé unique est
  négligeable face à l'objectif de 200 ms de la spec `005`.
