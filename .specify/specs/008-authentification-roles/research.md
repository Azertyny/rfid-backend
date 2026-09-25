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
