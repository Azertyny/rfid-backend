# Feature Specification: Chaîne de build, CI/CD et déploiement sur VPS

**Feature Branch**: `009-deploiement-vps`

**Created**: 2026-09-25

**Status**: Draft

## Clarifications

### Session 2026-09-25

- Q: Le déploiement en production doit-il être automatique à chaque fusion sur `main`, ou déclenché manuellement ? → A: Manuel — l'exploitant déclenche le déploiement d'une version publiée qu'il choisit.
- Q: Le déploiement sur une machine du réseau local de la ligne est-il abandonné ou conservé comme cible secondaire ? → A: Abandonné — le VPS est la seule cible de déploiement.
- Q: Les lecteurs RFID peuvent-ils envoyer leurs scans en HTTPS vers un nom de domaine public, ou seulement en HTTP simple ? → A: Oui — les lecteurs (ou leur passerelle) gèrent HTTPS avec un certificat public ; aucune exception HTTP pour la route de scan.
- Q: Comment l'exploitant déclenche-t-il un déploiement en production : depuis la chaîne d'intégration, ou en se connectant au VPS ? → A: Depuis la chaîne d'intégration — l'exploitant choisit la version, la chaîne se connecte au VPS, déploie et vérifie.
- Q: Où les sauvegardes quotidiennes sont-elles conservées ? → A: Hors du VPS, sur un stockage d'un autre fournisseur (compatible S3 par exemple).
- Q: Quel nom de domaine l'application utilise-t-elle sur le VPS ? → A: `vegelink.apolog.fr`.
- Q: La sécurisation du VPS lui-même (pare-feu, accès SSH, mises à jour système) fait-elle partie de la fonctionnalité ? → A: Oui, socle de base — pare-feu limité au web et à SSH, SSH par clé uniquement sans connexion root, mises à jour de sécurité automatiques.

**Input**: User description: "The historical deployement method aimed to deploy to a local server. And the github action CI does not work anymore. I would like to review all this build, deployement and CI/CD chaintool to deploy on a VPS"

> **État actuel (as-is)** — constaté le 2026-09-25 :
> - L'intégration continue (`.github/workflows/docker-image.yml`) échoue à chaque fusion sur `dev` depuis le 2026-09-24 (travail d'authentification, spec `008`). Un seul test échoue sur la plateforme d'intégration, `AuthFlowSecurityTest.login_returnsFreshCsrfCookieUsableForTheNextWrite` (« Expecting actual not to be null », ligne 120), alors qu'il passe en local. Comme les tests précèdent la publication, aucune image n'est publiée depuis.
> - La même chaîne construit et publie l'image pour `dev` et `main` sans distinction et marque `latest` quel que soit la branche.
> - Le déploiement documenté (`deploy/INSTALL.md`, `deploy/docker-compose.yml`) vise une machine du réseau de la ligne de comptage, en HTTP simple, avec une mise à jour entièrement manuelle. Deux fichiers d'exemple coexistent (`deploy/.env.example`, `deploy/.env.local.example`), tous deux sur l'image `latest`.
> - Une tentative antérieure de mise en production sur VPS (branche `feature/mep_vps`, jamais fusionnée) prévoyait HTTPS avec certificat renouvelé automatiquement sur `api.apolog.fr`, mais sans servir le front.
> - La configuration de production autorise par défaut l'origine `https://apolog.fr`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Une intégration continue fiable (Priority: P1)

Le développeur pousse une branche ou fusionne une pull request. La chaîne d'intégration construit l'application et exécute toute la suite de tests, puis donne un verdict fiable : vert si le code est bon, rouge avec la cause lisible sinon. Un test qui passe en local passe aussi dans la chaîne, et inversement.

**Why this priority**: aujourd'hui aucune image n'est produite parce que la chaîne est rouge. Rien de ce qui suit (publication, déploiement) n'est possible sans une intégration verte et digne de confiance.

**Independent Test**: ouvrir une pull request vers `dev` sans modification fonctionnelle et vérifier que la chaîne est verte ; y introduire un test volontairement faux et vérifier qu'elle devient rouge en nommant ce test.

**Acceptance Scenarios**:

1. **Given** le code actuel de `dev`, **When** la chaîne d'intégration s'exécute, **Then** tous les tests passent et le verdict est vert.
2. **Given** une pull request vers `dev` ou `main`, **When** elle est ouverte ou mise à jour, **Then** la chaîne construit et teste le code et son verdict apparaît sur la pull request avant la fusion.
3. **Given** un test en échec, **When** la chaîne s'exécute, **Then** le verdict est rouge, rien n'est publié ni déployé, et le nom du test en échec est visible sans fouiller le journal complet.
4. **Given** le même commit, **When** les tests sont lancés en local puis dans la chaîne, **Then** les deux résultats sont identiques.

---

### User Story 2 - Déployer une version sur le VPS (Priority: P1)

Une version validée de l'application (API, front et base de données) tourne sur un VPS accessible depuis Internet par un nom de domaine, en HTTPS. Les utilisateurs (Administrateurs, Opérateurs) s'y connectent depuis un navigateur ; les lecteurs RFID y envoient leurs scans. Le déploiement d'une nouvelle version suit une procédure unique, décrite et reproductible, qui conserve les données.

**Why this priority**: c'est l'objectif de la fonctionnalité ; sans VPS opérationnel, l'application n'est accessible qu'au réseau local.

**Independent Test**: sur un VPS vierge, suivre la documentation d'installation de bout en bout, se connecter avec le premier Administrateur, puis déployer une seconde version et vérifier que les données saisies avant sont toujours là.

**Acceptance Scenarios**:

1. **Given** un VPS vierge et la documentation d'installation, **When** l'exploitant la suit, **Then** l'application est accessible en HTTPS sur `vegelink.apolog.fr`, front et API sous la même adresse.
2. **Given** une version publiée par la chaîne, **When** l'exploitant déclenche son déploiement, **Then** la nouvelle version remplace l'ancienne et les données (cueilleurs, seaux, tags, lecteurs, lectures, utilisateurs) sont conservées.
3. **Given** un accès en HTTP simple, **When** un navigateur ouvre l'adresse, **Then** il est redirigé vers HTTPS.
4. **Given** un lecteur RFID configuré avec l'adresse du VPS et son jeton, **When** il envoie un scan, **Then** le scan est enregistré comme en local.
5. **Given** le certificat HTTPS approche de son expiration, **When** le délai de renouvellement est atteint, **Then** il est renouvelé sans intervention humaine.

---

### User Story 3 - Revenir en arrière après un mauvais déploiement (Priority: P2)

Après un déploiement, l'exploitant constate un défaut bloquant. Il remet en service la version précédente en une opération documentée, sans perte de données saisies entre-temps (dans la limite de la compatibilité du schéma de données).

**Why this priority**: un déploiement sur un serveur utilisé en pleine récolte doit pouvoir être annulé vite ; mais il ne sert qu'une fois les déploiements en place.

**Independent Test**: déployer une version A, puis une version B, puis revenir à A en suivant la procédure ; vérifier que A tourne et que les données sont intactes.

**Acceptance Scenarios**:

1. **Given** chaque version publiée porte un identifiant unique et traçable jusqu'au commit, **When** l'exploitant déclenche depuis la chaîne le déploiement d'une version précédente, **Then** cette version est remise en service.
2. **Given** un déploiement dont l'application ne démarre pas, **When** la vérification de santé échoue, **Then** l'échec est signalé à l'exploitant.

---

### User Story 4 - Sauvegarder et restaurer les données (Priority: P2)

Les données de production sont sauvegardées automatiquement chaque jour sur un stockage externe, chez un autre fournisseur que l'hébergeur du VPS, et l'exploitant sait restaurer une sauvegarde, y compris sur un VPS neuf.

**Why this priority**: sur un VPS, la perte du serveur ou une erreur de manipulation (ex. suppression du volume de données) effacerait toute une saison de récolte. En local, la machine était physiquement sous contrôle ; plus maintenant.

**Independent Test**: laisser tourner une sauvegarde planifiée, vérifier sa présence sur le stockage externe, la restaurer sur une instance vierge, vérifier que les données correspondent.

**Acceptance Scenarios**:

1. **Given** l'application en production, **When** 24 heures passent, **Then** au moins une sauvegarde complète des données a été déposée sur le stockage externe.
2. **Given** une sauvegarde, **When** l'exploitant suit la procédure de restauration, **Then** l'application redémarre avec les données de cette sauvegarde.
3. **Given** le VPS est perdu, **When** l'exploitant installe un VPS neuf et y restaure la dernière sauvegarde externe, **Then** l'application reprend avec les données de cette sauvegarde.

---

### User Story 5 - Nettoyer la chaîne historique (Priority: P3)

La documentation et les fichiers de déploiement ne décrivent plus qu'une seule façon de faire, à jour. Les fichiers d'exemple cités existent, les secrets ne sont jamais versionnés, et le démarrage local pour le développement reste inchangé.

**Why this priority**: évite qu'un futur exploitant suive une procédure obsolète ; utile mais pas bloquant.

**Independent Test**: relire la documentation de déploiement et vérifier que chaque fichier et chaque commande cités existent et fonctionnent.

**Acceptance Scenarios**:

1. **Given** le dépôt, **When** on cherche une procédure de déploiement, **Then** on n'en trouve qu'une, celle du VPS ; le déploiement sur une machine du réseau local de la ligne n'est plus décrit ni outillé.
2. **Given** le dépôt, **When** on cherche des mots de passe, jetons ou clés, **Then** aucun secret réel n'y figure ; seuls des exemples avec des valeurs factices.
3. **Given** un développeur, **When** il lance l'application en local comme décrit dans `CLAUDE.md`, **Then** elle démarre comme avant.

### Edge Cases

- L'exploitant déclenche un déploiement alors que le précédent est encore en cours : les déploiements ne doivent pas se chevaucher.
- L'exploitant demande le déploiement d'une version qui n'existe pas ou n'a pas passé l'intégration : le déploiement est refusé.
- Le premier démarrage sur le VPS se fait sans variables de l'Administrateur initial : l'application démarre mais personne ne peut se connecter ; la documentation doit l'empêcher ou l'expliquer.
- Le nom de domaine ne pointe pas encore vers le VPS au moment de l'installation : l'obtention du certificat échoue ; la procédure doit le détecter et le dire.
- Le disque du VPS se remplit (images accumulées, sauvegardes, journaux) : les anciennes versions et sauvegardes au-delà de la rétention sont purgées.
- Une modification de schéma de données rend un retour arrière incompatible avec les données : la procédure de retour arrière doit prévenir de ce risque (restauration de sauvegarde nécessaire).
- Le VPS redémarre (maintenance de l'hébergeur) : tous les services repartent seuls.
- La liaison Internet de la ligne de comptage est coupée : les lecteurs ne peuvent plus envoyer de scans au VPS (voir Assumptions).
- Le stockage externe est injoignable au moment de la sauvegarde : la sauvegarde est signalée en échec (FR-017b) et retentée à l'échéance suivante.
- Un secret (mot de passe base, accès au VPS, accès au stockage externe) doit être changé : la procédure de rotation est documentée.

## Requirements *(mandatory)*

### Functional Requirements

**Intégration continue**

- **FR-001**: La chaîne d'intégration MUST construire l'application et exécuter toute la suite de tests pour chaque pull request vers `dev` et `main` et pour chaque fusion sur ces branches.
- **FR-002**: La suite de tests MUST donner le même résultat en local et dans la chaîne d'intégration ; le test actuellement en échec dans la chaîne MUST être corrigé à sa cause, ni désactivé ni ignoré.
- **FR-003**: La chaîne MUST s'arrêter sans rien publier ni déployer si la construction ou un test échoue, et MUST rendre visible le nom des tests en échec.
- **FR-004**: Le verdict de la chaîne MUST être visible sur la pull request avant sa fusion.

**Publication des versions**

- **FR-005**: Seul un code ayant passé FR-001 MUST pouvoir être publié comme version déployable.
- **FR-006**: Chaque version publiée MUST porter un identifiant unique, traçable jusqu'au commit source ; les versions issues de `main` MUST être distinguables de celles issues de `dev`.
- **FR-007**: Une version publiée MUST contenir tout ce qui est nécessaire à l'exécution de l'application, front compris, de sorte que le déploiement ne dépende pas d'une copie du dépôt sur le VPS.

**Déploiement sur le VPS**

- **FR-008**: L'application (front, API, base de données) MUST être servie depuis le VPS sous le seul nom de domaine `vegelink.apolog.fr`, front et API à la même origine, exclusivement en HTTPS ; les accès HTTP MUST être redirigés vers HTTPS.
- **FR-009**: Le certificat HTTPS de `vegelink.apolog.fr` MUST être obtenu et renouvelé automatiquement.
- **FR-009b**: En production, l'API MUST n'accepter les requêtes de navigateur que depuis l'origine `https://vegelink.apolog.fr` ; l'origine par défaut actuelle `https://apolog.fr` MUST être remplacée.
- **FR-009a**: La réception des scans des lecteurs RFID MUST suivre la même règle que le reste de l'application : HTTPS uniquement, sans exception en HTTP simple ; un scan envoyé en HTTP n'est pas enregistré et le jeton n'est jamais accepté hors HTTPS.
- **FR-010**: Seul le point d'entrée web MUST être exposé sur Internet ; la base de données et l'API ne MUST pas être joignables directement de l'extérieur.
- **FR-010a**: Le pare-feu du VPS MUST n'autoriser en entrée que le web (HTTP pour la redirection et l'obtention du certificat, HTTPS) et l'accès d'administration à distance (SSH).
- **FR-010b**: L'accès SSH au VPS MUST se faire uniquement par clé ; la connexion par mot de passe et la connexion directe en root MUST être refusées.
- **FR-010c**: Les mises à jour de sécurité du système du VPS MUST s'appliquer automatiquement.
- **FR-011a**: Le déploiement en production MUST être déclenché manuellement par l'exploitant depuis la chaîne d'intégration, en choisissant la version à déployer parmi les versions publiées issues de `main` ; la chaîne se connecte alors au VPS, déploie puis exécute la vérification de santé (FR-012). Aucune fusion ne MUST déclencher de déploiement automatique.
- **FR-011b**: Chaque déploiement MUST laisser dans la chaîne une trace consultable : version déployée, auteur du déclenchement, date, résultat de la vérification de santé.
- **FR-011c**: L'accès de la chaîne au VPS MUST se limiter au déploiement : un compte dédié, sans droits d'administration du système, dont l'identifiant d'accès est stocké comme secret de la chaîne et révocable sans toucher aux autres accès au VPS.
- **FR-011**: Le déploiement d'une version MUST conserver les données existantes et MUST suivre une procédure unique et documentée.
- **FR-012**: Après chaque déploiement, une vérification de santé MUST confirmer que l'application répond ; un échec MUST être signalé à l'exploitant.
- **FR-013**: Les services MUST redémarrer d'eux-mêmes après un redémarrage du VPS ou un arrêt inattendu.
- **FR-014**: L'exploitant MUST pouvoir remettre en service une version précédente identifiée (FR-006) par le même déclenchement que FR-011a, en choisissant cette version.
- **FR-015**: Les secrets (mot de passe de la base, Administrateur initial, accès au VPS) MUST être fournis hors du dépôt ; le dépôt MUST contenir un exemple de configuration avec des valeurs factices et la liste complète des variables attendues.
- **FR-016**: Les déploiements MUST être sérialisés : un déploiement ne démarre pas tant que le précédent n'est pas terminé.

**Données**

- **FR-017**: Les données de production MUST être sauvegardées automatiquement au moins une fois par jour, et les sauvegardes MUST être conservées au moins 14 jours sur un stockage externe, chez un fournisseur distinct de l'hébergeur du VPS, de sorte qu'elles survivent à la perte totale du VPS.
- **FR-017a**: Les identifiants d'accès au stockage externe MUST être traités comme les autres secrets (FR-015) ; le VPS n'a besoin que du droit d'y déposer et d'y lire les sauvegardes.
- **FR-017b**: L'échec d'une sauvegarde planifiée MUST être visible par l'exploitant sans devoir se connecter au VPS.
- **FR-018**: Une procédure de restauration d'une sauvegarde MUST être documentée et avoir été exécutée avec succès au moins une fois.

**Documentation et nettoyage**

- **FR-019**: La documentation de déploiement MUST décrire l'installation d'un VPS vierge (sécurisation FR-010a à FR-010c comprise), le déploiement d'une version, le retour arrière, la sauvegarde, la restauration et la rotation des secrets ; chaque fichier et commande cités MUST exister.
- **FR-020**: Les fichiers et la documentation propres au déploiement sur serveur local MUST être retirés ou remplacés ; le VPS est la seule cible de déploiement.
- **FR-021**: Le lancement local pour le développement (profil `dev`) MUST rester inchangé.

### Key Entities

- **Version publiée** : artefact déployable produit par la chaîne ; identifiant unique, commit source, branche d'origine (`dev` / `main`), date.
- **Déploiement** : action déclenchée par l'exploitant ; version déployée, auteur, date, résultat de la vérification de santé.
- **Environnement** : lieu où tourne une version (le VPS de production) ; nom de domaine (`vegelink.apolog.fr`), version en service, configuration et secrets associés.
- **Sauvegarde** : copie datée des données de production, déposée sur le stockage externe ; date, version de l'application au moment de la copie, échéance de rétention.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % des exécutions de la chaîne d'intégration sur le code de `dev` à jour sont vertes, sur 10 exécutions consécutives.
- **SC-002**: Le verdict d'intégration d'une pull request est disponible en moins de 10 minutes.
- **SC-003**: Une version est publiée moins de 15 minutes après la fusion sur `main`, et une fois le déploiement déclenché par l'exploitant, elle est en service en moins de 5 minutes.
- **SC-004**: Un exploitant qui ne connaît pas le projet installe un VPS vierge en suivant uniquement la documentation, en moins d'une heure.
- **SC-005**: Un retour à la version précédente prend moins de 5 minutes.
- **SC-006**: L'interruption de service pendant un déploiement ne dépasse pas 1 minute.
- **SC-007**: Aucune donnée n'est perdue lors d'un déploiement ou d'un retour arrière sans changement de schéma (vérifié sur 3 déploiements successifs).
- **SC-008**: Une restauration de sauvegarde complète depuis le stockage externe aboutit en moins de 30 minutes.
- **SC-010**: Après la perte simulée du VPS, l'application est remise en service sur un VPS neuf avec les données de la veille en moins de 2 heures.
- **SC-011**: Depuis l'extérieur, seuls les accès web et SSH répondent sur le VPS, et une tentative de connexion SSH par mot de passe ou en root est refusée.
- **SC-009**: Aucun secret réel n'est présent dans l'historique du dépôt après la fonctionnalité.

## Assumptions

- Un VPS Linux unique suffit pour la charge actuelle (une ligne de comptage, quelques utilisateurs, quelques lecteurs) ; pas de haute disponibilité ni de répartition de charge.
- Le nom de domaine est `vegelink.apolog.fr` (confirmé en clarification) ; l'exploitant contrôle la zone DNS de `apolog.fr` et y fait pointer ce sous-domaine vers le VPS. Le front et l'API sont servis à la même origine, ce qui évite les contraintes de cookies et de CSRF inter-origines de la session utilisateur (spec `008`).
- Un abonnement à un stockage externe (autre fournisseur que l'hébergeur du VPS) est souscrit par l'exploitant ; son coût est négligeable au volume de données d'une saison.
- L'hébergement de la chaîne d'intégration et du registre de versions reste celui utilisé aujourd'hui (plateforme d'hébergement du dépôt).
- Les lecteurs RFID de la ligne ont un accès Internet et joignent le VPS en HTTPS avec un certificat public (confirmé en clarification) ; seule leur adresse cible change. Le fonctionnement hors ligne des lecteurs (mise en file d'attente des scans pendant une coupure) est hors périmètre.
- Aucune donnée de production du serveur local n'est à migrer vers le VPS : la production démarre à vide, le premier Administrateur étant créé au démarrage (spec `008`).
- Le schéma de données continue d'évoluer par mise à jour automatique au démarrage ; l'introduction d'un outil de migration de schéma est hors périmètre.
- La protection contre les tentatives de connexion répétées (sur SSH comme sur la page de connexion de l'application) est hors périmètre ; seul le socle FR-010a à FR-010c est requis.
- La supervision avancée (métriques, alertes applicatives, centralisation des journaux) est hors périmètre ; seule la vérification de santé post-déploiement (FR-012) est requise.
- L'exploitant est le développeur du projet ; « signalé à l'exploitant » signifie visible dans la chaîne d'intégration ou par la notification standard de la plateforme.
- Un environnement de recette distinct de la production n'est pas requis ; les versions issues de `dev` sont publiées mais ne sont pas destinées à la production.
- L'abandon du serveur local implique que la ligne de comptage dépend de sa liaison Internet pour enregistrer les scans (cf. hypothèse sur le hors-ligne).
