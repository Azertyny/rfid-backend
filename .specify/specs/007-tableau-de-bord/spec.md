# Feature Specification: Tableau de bord (index.html)

**Feature Branch**: `007-tableau-de-bord` (as-is part reverse-engineered from the codebase)

**Created**: 2026-09-24

**Status**: Target implemented (Clarifications 2026-09-25) — backend and page delivered, SC-001 and SC-002 verified; only the production token rotation (T019) remains

**Input**: User description: "Parcours le dépôt. Pour chaque fonctionnalité métier identifiée, crée .specify/specs/NNN-nom-feature/spec.md ... Décris le comportement ACTUEL (as-is) ... cite les fichiers ... Marque [NEEDS CLARIFICATION] ..."

> **Résolu (2026-09-25)** : aucune section de `doc/` ne décrit ce tableau de bord ; il est défini par les Clarifications 2026-09-25 ci-dessous. Il n'existe que dans le code front (`front/index.html`) et l'API OpenAPI (`api.yaml`) qu'il consomme — cette spec compare donc le front à l'API plutôt qu'à un texte métier.

## Clarifications

### Session 2026-09-24

- Q: The dashboard calls GET /api/tags, which doesn't exist, so it never shows data; what it actually needs is today's scan events (Records). Should it be kept and backed by a real endpoint, or removed? → A: Keep the page, but do not add an endpoint for now — the dashboard stays in /front and in the deployment; its data source is deferred, so it keeps showing no production data until then.
- Q: index.html has a reader API token hardcoded in plain text (committed 2026-03-18, so also in git history and the deployed page). What should be done with it? → A: Remove and rotate (recommended) — remove it from index.html and treat it as compromised: regenerate that reader's token once the revoke/rotate route from spec 002 exists.
- Q: Once auth exists, the dashboard can no longer call /readers and /pickers anonymously. Who should be able to open the dashboard? → A: Opérateur and Admin (recommended) — any logged-in user with the Opérateur or Administrateur role; read-only.
- Q: Opérateurs need the reader list (dashboard line selector, reader.html), but GET /readers was planned Administrateur-only because it returns API tokens. How should Opérateurs get the list? → A: Hide tokens from Opérateurs (recommended) — GET /readers is open to both roles; tokens are only included for Administrateurs.

### Session 2026-09-25

- Q: How much should this iteration build: a fixed dashboard or the mockup's free-form pivot builder (`doc/tableau_de_bord_bi_analyses.html`)? → A: Fixed dashboard — summary figures (scans, conformity rate), a per-picker table (scans, non-conforming, rate), scans per hour, a reader/period filter, CSV export. The drag-and-drop pivot builder, saved views (bookmarks) and heatmap are out of scope for this iteration.
- Q: Where should the dashboard's numbers be calculated: by a new backend endpoint that returns totals, or in the browser from a new endpoint that lists raw scans? → A: New backend totals endpoint `GET /records/stats` (filters: period, reader) returning the summary, per-picker and per-hour totals computed in SQL; the browser only displays them. Supersedes the 2026-09-24 decision "no endpoint for now".
- Q: Which periods can the user pick, and in which time zone do "a day" and "an hour" start and end? → A: Today / Yesterday / Last 7 days / custom date range (max 31 days); days and hours computed on the server in a configurable station time zone, default `Europe/Paris`.
- Q: When a scan's conformity was changed by hand after the scan (spec 005), which value should the dashboard count? → A: The current (corrected) value only — `record.conformity`, no join to the conformity history.
- Q: How should the dashboard count scans that aren't linked to any picker (unknown tag or bucket unassigned at scan time)? → A: Count them in the summary and show them as a separate "Non attribué" row in the picker table, so the picker rows always add up to the summary.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Visualiser l'activité de production (Priority: P1 — cible Clarifications 2026-09-25)

Un utilisateur ouvre `front/index.html` pour voir un tableau de bord : sélecteur de ligne, grille des cueilleurs actifs, graphique de cadence horaire, statut de connexion.

**Evidence**: `front/index.html:154-330`.

**Cible (Clarifications 2026-09-25)** : un Opérateur ou un Administrateur ouvre la page, choisit une période (FR-007) et éventuellement un lecteur, et voit la synthèse, le tableau par cueilleur et la répartition horaire calculés par `GET /records/stats` (FR-002, FR-005), puis peut exporter le tableau par cueilleur en CSV.

**Acceptance Scenarios**:

**Constat as-is (avant 2026-09-25)** :

1. **Given** la page est chargée, **When** `fetchReaders()`/`fetchPickersInfo()`/`fetchData()` s'exécutent en boucle (polling), **Then** la page tente d'appeler `GET /api/readers`, `GET /api/pickers?size=500` et `GET /api/tags?size=3000&_t=<timestamp>`. Evidence: `front/index.html:177-226`.
2. **Given** `GET /api/tags` est appelé, **When** on compare à `api.yaml`, **Then** cette route **n'existe pas** dans la spécification OpenAPI ni dans `TagApiDelegate` généré (seules `POST /tags/buckets/{bucketNumber}` et `POST /tags/scan` existent, `api.yaml:156-214`). L'appel échouera systématiquement (404 / route non mappée). Voir Edge Cases.

**Acceptance Scenarios (cible)** :

3. **Given** 3 lectures aujourd'hui (2 d'un cueilleur, 1 d'un tag sans seau), **When** un Opérateur ouvre la page (période par défaut Aujourd'hui), **Then** la synthèse affiche 3 lectures, le tableau une ligne cueilleur à 2 et une ligne « Non attribué » à 1 en dernier, et le total du tableau vaut 3.
4. **Given** une lecture corrigée en non conforme (spec `005`), **When** la page se rafraîchit, **Then** elle est comptée non conforme (FR-008).
5. **Given** une période sans lecture, **When** on la sélectionne, **Then** la page affiche « Aucune lecture sur la période », sans données de démonstration.
6. **Given** une plage personnalisée de 32 jours, **When** on valide, **Then** la page affiche le message d'erreur et n'affiche pas de chiffres.
7. **Given** un lecteur sélectionné, **When** la page se charge, **Then** seuls les chiffres de ce lecteur sont affichés.
8. **Given** des chiffres affichés, **When** on clique « Exporter CSV », **Then** le fichier s'ouvre dans Excel FR avec les accents, une colonne par champ et une ligne Total égale à la synthèse.

### Edge Cases

- **Fonctionnalité non opérationnelle** : `processBusinessLogic(allTags)`, `updatePickersGrid(tags)`, `initChart()`/`updateChart(tags)` (`front/index.html:228-318`) dépendent tous du résultat de `fetchData()`, lui-même dépendant d'un endpoint `GET /api/tags` inexistant côté backend. En l'état actuel du dépôt, cette page ne peut pas afficher de données de tags/cadence réelles. Constat : les champs lus par `processBusinessLogic` (`processedAt`, `isCompliant`, `readerId`, `front/index.html:231-235`) sont ceux d'un événement de scan (`Record`), pas d'un `Tag` — la source attendue est donc un flux de lectures du jour, pas une liste de tags. **Résolu (2026-09-24)** : la page est conservée, mais aucun endpoint n'est ajouté pour l'instant ; la source de données est reportée à une itération ultérieure. **Remplacé (2026-09-25)** : la page est alimentée par un nouvel endpoint d'agrégats `GET /records/stats` (FR-002) ; l'appel à `GET /api/tags` est supprimé.
- **Jeton d'API codé en dur dans une page publique** : `const API_TOKEN = "176c77ca6757494f9729784263c6022d";` (`front/index.html:155`) est un secret embarqué en clair dans un fichier HTML statique, servi tel quel via `deploy/front` (configuration nginx `deploy/front/default.conf`) et le `Dockerfile`. Ce jeton correspond au format généré par `ReaderEntity.prePersist` (`ReaderEntity.java:45-50`, UUID sans tirets) — il s'agit donc vraisemblablement d'un vrai jeton de lecteur, pas d'un placeholder. Il est envoyé sur des routes qui ne le vérifient même pas aujourd'hui (`/readers`, `/pickers` sont `permitAll()`, voir spec `001`/`002`). Le jeton est présent dans l'historique git depuis le commit `c68fb98` (2026-03-18) et absent de la base H2 locale, ce qui suggère un jeton de lecteur de production. **Résolu (2026-09-24)** : retirer le jeton de `index.html` et le considérer comme compromis — le retirer du fichier ne suffit pas puisqu'il reste dans l'historique git ; le jeton du lecteur concerné DOIT être régénéré dès que la route de révocation/rotation de la spec `002` existe.
- **Accès à la page une fois l'authentification en place** : les deux appels qui fonctionnent aujourd'hui (`/readers`, `/pickers`) seront authentifiés (specs `001`, `002`). **Résolu (2026-09-24)** : la page est accessible à tout utilisateur connecté de rôle Opérateur ou Administrateur, en lecture seule. `GET /readers` est ouvert à ces deux rôles, mais les jetons ne sont renvoyés qu'aux Administrateurs (spec `002`) ; la page n'a pas besoin des jetons.
- **Fuseau horaire** (Clarifications 2026-09-25) : une lecture à 00:30 heure de Paris appartient au jour de Paris, et une lecture à 07:15 heure de Paris est comptée dans la tranche 07h, y compris lors des changements d'heure (été/hiver) et si le serveur tourne en UTC.
- **Lectures sans cueilleur** (Clarifications 2026-09-25) : tag inconnu au scan ou seau sans cueilleur → ligne « Non attribué » (FR-009). Aucune attribution par déduction (le prototype `doc/tableau_de_bord_bi_analyses.html` attribuait les lectures par `index % nombre de cueilleurs` — interdit).
- **Aucune lecture sur la période** : la page affiche un état vide explicite ; elle NE DOIT JAMAIS afficher de données de démonstration générées (contrairement au prototype).
- **Pas de test** : aucun outillage de test front (pas de `package.json`, pas de suite JS) n'existe dans le dépôt pour vérifier ce fichier.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La page DOIT alimenter le filtre lecteur avec `GET /api/readers` et NE DOIT PLUS appeler `GET /api/pickers` : les noms des cueilleurs viennent de `GET /records/stats`. Tous les lecteurs sont listés, suffixés « (désactivé) » ou « (enregistrement) » : un lecteur désactivé ou passé en mode `ENREGISTREMENT` garde ses lectures de production passées, consultables sur une période antérieure (research R6). Cela remplace, pour ce filtre, la note de la feature `003` qui masquait ces lecteurs dans la vue temps réel. *Avant (as-is)* : `GET /api/readers` et `GET /api/pickers?size=500`, `front/index.html:177-215`.
- **FR-002**: **Livré (2026-09-25)**. Avant — la page tentait d'interroger `GET /api/tags?size=3000`, endpoint absent de l'API réelle (`front/index.html:217-226` vs `api.yaml`). **Décision (Clarifications 2026-09-25, remplace celle du 2026-09-24)** : un nouvel endpoint `GET /records/stats` (défini d'abord dans `api.yaml`, tag `Record`) DOIT renvoyer, pour une période et un lecteur optionnel, les agrégats calculés côté serveur en SQL (`GROUP BY`) : synthèse (lectures, non conformes), totaux par cueilleur, totaux par tranche horaire. La page NE DOIT PAS télécharger de lectures brutes ni agréger dans le navigateur ; l'appel à `GET /api/tags` DOIT être supprimé.
- **FR-003**: Avant — la page envoyait un jeton d'API codé en dur (`x-api-token`) sur chacun de ces appels. Evidence: `front/index.html:155,179,209,219`. **Décision (Clarifications 2026-09-24)** : la page NE DOIT PAS contenir de jeton (**livré** : la page n'appelle que `apiFetch`) ; le jeton exposé DOIT être régénéré via la route `POST /readers/{readerId}/token` (spec `002`, disponible) — tâche opérationnelle T019.
- **FR-004** (nouveau, Clarifications 2026-09-24) : la page DOIT être accessible aux utilisateurs connectés de rôle Opérateur ou Administrateur, en lecture seule. **Livré** par la spec `008` (`/api/records/**` → Administrateur, Opérateur) et `requireRole` dans la page.
- **FR-005** (Clarifications 2026-09-25) : la page DOIT être un tableau de bord **à structure fixe** composé de : (a) indicateurs de synthèse — nombre de lectures et taux de conformité sur la période filtrée ; (b) tableau par cueilleur — lectures, lectures non conformes, taux de conformité ; (c) répartition des lectures par tranche horaire ; (d) filtre par lecteur et par période ; (e) export CSV du tableau par cueilleur (UTF-8 avec BOM, séparateur `;`, pour Excel FR).
- **FR-006** (Clarifications 2026-09-25) : hors périmètre de cette itération — constructeur de tableau croisé par glisser-déposer, choix libre des axes, signets/vues sauvegardées, heatmap (proposés par `doc/sp_cification_bi_pour_claude_code.md`).
- **FR-007** (Clarifications 2026-09-25) : le filtre de période DOIT proposer Aujourd'hui, Hier, 7 derniers jours et une plage de dates personnalisée (jours de début et de fin inclus) d'au plus 31 jours. Les bornes de jour et les tranches horaires DOIVENT être calculées côté serveur dans le fuseau de la station, configurable (propriété applicative), `Europe/Paris` par défaut — indépendamment du fuseau du serveur et du navigateur. Une plage de plus de 31 jours ou dont le début est après la fin DOIT être refusée (400).
- **FR-008** (Clarifications 2026-09-25) : la conformité comptée DOIT être la valeur courante de la lecture (`record.conformity`), c'est-à-dire après d'éventuelles corrections manuelles (spec `005`) ; le verdict d'origine du lecteur n'est pas utilisé. Une correction faite après un premier affichage est visible au rafraîchissement suivant.
- **FR-009** (Clarifications 2026-09-25) : une lecture est attribuée au cueilleur enregistré sur la lecture au moment du scan (`record.picker`), pas à l'affectation actuelle du seau. Les lectures sans cueilleur DOIVENT être comptées dans la synthèse et regroupées dans une ligne « Non attribué » du tableau par cueilleur ; la somme des lignes du tableau par cueilleur DOIT égaler la synthèse, et la somme des tranches horaires aussi.

### Key Entities

Aucune nouvelle entité persistée — la page lit `Reader` (filtre) et les agrégats de `Record` (avec `Picker`) via `GET /records/stats`. Le type de caisse et le poids proposés par `doc/sp_cification_bi_pour_claude_code.md` n'existent pas dans le modèle (`BucketEntity` n'a ni type ni poids) et ne sont pas introduits.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (Clarifications 2026-09-25) : pour un jeu de lectures connu, les totaux renvoyés par `GET /records/stats` (synthèse, par cueilleur, par tranche horaire) sont exacts — vérifié par tests backend — quel que soit le volume de lectures de la période (pas de troncature).
- **SC-002** (analyse 2026-09-25) : `GET /records/stats` répond en moins de 1 s pour une plage de 31 jours contenant 200 000 lectures, sur PostgreSQL (vérifié par `quickstart.md` section 5). **Vérifié le 2026-09-25** : PostgreSQL 16, 200 000 lectures sur 31 jours, 0,12 s (0,24 s au premier appel), avec et sans lecteur ; totaux exacts.

## Assumptions

- **Confirmé (Clarifications 2026-09-24)** : cette page est conservée (pas du code mort à retirer). Sa source de données, reportée le 2026-09-24, est définie le 2026-09-25 (FR-002).

## Drift vs `doc/`

| Point documenté (`doc/`) | Comportement réel | Fichiers |
|---|---|---|
| Aucune section `doc/` ne décrit de tableau de bord | Page complète existe en front, partiellement non fonctionnelle | `front/index.html` |
| — | Jeton d'API en clair dans le code source front public ; cible (Clarifications 2026-09-24) = retrait et rotation du jeton | `front/index.html:155` |
| — | Appel à un endpoint `GET /api/tags` absent de `api.yaml` : **livré (2026-09-25)**, remplacé par `GET /records/stats` (agrégats serveur) | `front/index.html:219`, `api.yaml` |
