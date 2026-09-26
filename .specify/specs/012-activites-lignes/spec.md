# Feature Specification: Activités des lignes de production

**Feature Branch**: `012-activites-lignes`

**Created**: 2026-09-26

**Status**: Delivered (2026-09-26)

**Input**: User description: "J'aimerais ajouter une notion \"d'activité\" dans l'application. L'objectif étant de pouvoir sélectionner le type de produit en cours de production pour une ligne donnée, dons pour un lecteur donné. Ensuite, un opérateur de la ligne pourra sélectionner l'activité en cours sur sa ligne. Les activités seront associées aux lignes par l'administrateur en amont."

## Contexte

Une ligne de production correspond à un lecteur en mode "production" (spec `002`). Chaque seau présenté au lecteur
crée une lecture (spec `004`), que l'Opérateur de la ligne consulte et dont il peut revoir la conformité depuis le
kiosque tactile de la ligne (specs `005` et `008`). Aujourd'hui, rien n'indique quel produit la ligne traite : une
même ligne peut passer d'un produit à un autre dans la journée, et les lectures ne permettent pas de le savoir.

Cette fonctionnalité introduit l'**activité** : le type de produit en cours de production. L'Administrateur tient la
liste des activités et indique, pour chaque ligne, celles qu'elle peut traiter. L'Opérateur de la ligne choisit parmi
elles l'activité en cours, et la change quand la ligne change de produit.

## Clarifications

### Session 2026-09-26

- Q: L'activité doit-elle être enregistrée sur chaque lecture, ou seulement affichée comme état de la ligne ? → A: Enregistrée sur chaque lecture au moment du scan, et figée ensuite (traçabilité et statistiques par produit possibles).
- Q: Le filtre par activité du tableau de bord (spec `007`) et de son export CSV fait-il partie de cette fonctionnalité ? → A: Non, reporté à une fonctionnalité ultérieure ; les lectures portent déjà l'activité, donc rien n'est perdu d'ici là.
- Q: Si l'Opérateur a oublié de changer d'activité, peut-on corriger après coup l'activité des lectures déjà faites ? → A: Non : l'activité d'une lecture est figée à sa création, aucune correction n'est prévue dans cette fonctionnalité.
- Q: L'activité en cours d'une ligne doit-elle persister d'un jour à l'autre, ou repartir de « aucune activité » chaque jour ? → A: Remise à zéro chaque jour à minuit (fuseau de la station) : toutes les lignes repassent à « aucune activité », l'Opérateur choisit l'activité en début de poste.
- Q: Quand une ligne n'a pas d'activité en cours, comment le kiosque doit-il le signaler à l'Opérateur ? → A: Bandeau d'alerte bien visible en haut du kiosque, tant qu'aucune activité n'est choisie, avec le nombre de lectures faites sans activité depuis minuit ; aucun blocage.

## User Scenarios & Testing _(mandatory)_

### User Story 1 - L'Administrateur définit les activités et les associe aux lignes (Priority: P1)

L'Administrateur crée les activités (par exemple "Fraise gariguette", "Framboise") et, pour chaque ligne, coche celles
que la ligne peut traiter. Il peut renommer une activité, la désactiver quand elle n'est plus produite, et modifier à
tout moment les associations.

**Why this priority**: sans activités ni associations, l'Opérateur n'a rien à choisir ; c'est le préalable de tout le
reste.

**Independent Test**: un Administrateur crée deux activités, en associe une à la ligne L1 et les deux à la ligne L2 ;
la liste des activités de chaque ligne reflète exactement ces choix.

**Acceptance Scenarios**:

1. **Given** un Administrateur connecté, **When** il crée l'activité "Framboise", **Then** elle apparaît dans la liste
   des activités, active, associée à aucune ligne.
2. **Given** une activité "Framboise" existante, **When** un Administrateur crée une autre activité nommée
   "framboise " (casse et espaces différents), **Then** la création est refusée avec un message indiquant que le nom
   existe déjà.
3. **Given** les activités "Fraise" et "Framboise", **When** l'Administrateur associe "Fraise" à la ligne L1,
   **Then** L1 propose "Fraise" seulement, et les autres lignes sont inchangées.
4. **Given** une activité associée à des lignes, **When** l'Administrateur la renomme, **Then** le nouveau nom
   apparaît partout, y compris sur les lectures passées qui la portent.
5. **Given** un Opérateur connecté, **When** il tente de créer, modifier une activité ou une association, **Then**
   l'action est refusée.

---

### User Story 2 - L'Opérateur choisit l'activité en cours sur sa ligne (Priority: P1)

Au kiosque de sa ligne, l'Opérateur voit l'activité en cours (ou l'absence d'activité) et peut la changer en
choisissant parmi les activités associées à la ligne. Le changement prend effet immédiatement pour les lectures
suivantes.

**Why this priority**: c'est l'usage quotidien visé par la demande.

**Independent Test**: au kiosque de la ligne L1, qui a deux activités associées, l'Opérateur choisit la seconde ;
le kiosque l'affiche comme activité en cours, et les lectures suivantes de L1 la portent.

**Acceptance Scenarios**:

1. **Given** la ligne L1 associée à "Fraise" et "Framboise", sans activité en cours, **When** l'Opérateur choisit
   "Framboise" au kiosque de L1, **Then** "Framboise" devient l'activité en cours de L1 et le kiosque l'affiche.
2. **Given** le kiosque de L1, **When** il tente de choisir une activité non associée à L1, ou de changer l'activité
   en cours d'une autre ligne, **Then** c'est refusé et les activités en cours sont inchangées. Choisir une activité
   associée à L1 reste permis même si elle est aussi en cours sur une autre ligne.
3. **Given** "Fraise" en cours sur L1, **When** l'Opérateur choisit "Framboise", **Then** les lectures faites avant
   le changement gardent "Fraise" et celles faites après portent "Framboise".
4. **Given** une activité en cours sur L1, **When** l'Opérateur choisit "aucune activité", **Then** L1 n'a plus
   d'activité en cours et les lectures suivantes n'en portent pas.
5. **Given** un Opérateur ou un Administrateur connecté au front, **When** il choisit l'activité en cours d'une
   ligne, **Then** le choix est accepté aux mêmes conditions qu'au kiosque.
6. **Given** un lecteur en mode "enregistrement", **When** on tente de lui associer une activité ou de lui choisir une
   activité en cours, **Then** c'est refusé : seules les lignes de production ont une activité.

---

### User Story 3 - Chaque lecture garde l'activité en cours au moment du scan (Priority: P2)

Chaque lecture de production enregistre l'activité en cours sur sa ligne à l'instant du scan. On peut ainsi savoir,
après coup, quel produit était traité, même si la ligne a changé d'activité depuis.

**Why this priority**: c'est ce qui rend l'activité utile au-delà de l'affichage au kiosque ; il dépend de la story 2.

**Independent Test**: une lecture faite pendant "Fraise" puis une autre après passage à "Framboise" portent chacune
la bonne activité, et le kiosque affiche l'activité de chaque lecture.

**Acceptance Scenarios**:

1. **Given** "Fraise" en cours sur L1, **When** L1 scanne un tag, **Then** la lecture porte "Fraise".
2. **Given** aucune activité en cours sur L1, **When** L1 scanne un tag, **Then** la lecture est créée comme
   aujourd'hui, sans activité : un scan n'est jamais refusé faute d'activité.
3. **Given** une lecture portant "Fraise", **When** l'activité en cours de L1 change ensuite, ou que "Fraise" est
   dissociée de L1 ou désactivée, **Then** la lecture porte toujours "Fraise".
4. **Given** un scan ignoré comme doublon (spec `004`, FR-008), **When** l'activité a changé entre les deux scans,
   **Then** la lecture existante garde son activité d'origine.

### Edge Cases

- **Ligne sans activité associée** : le bandeau d'alerte (FR-009a) indique en plus qu'aucune activité n'est
  disponible pour la ligne et invite à contacter l'Administrateur ; les scans continuent sans activité.
- **Dissociation de l'activité en cours** : si l'Administrateur retire à une ligne l'activité en cours, la ligne n'a
  plus d'activité en cours ; les lectures suivantes n'en portent pas jusqu'au prochain choix de l'Opérateur.
  L'Administrateur est prévenu avant de confirmer.
- **Désactivation d'une activité en cours sur une ou plusieurs lignes** : même effet que la dissociation, pour chacune
  de ces lignes ; l'activité n'est plus proposée au choix, mais reste visible sur les lectures passées et dans les
  associations (marquée désactivée) pour pouvoir être réactivée.
- **Suppression d'une activité** : impossible dès qu'une lecture la porte ou qu'elle a déjà été choisie sur une ligne
  (il faut la désactiver) ; possible sinon, ce qui retire aussi ses associations.
- **Deux choix simultanés sur la même ligne** (kiosque et Opérateur connecté) : le dernier l'emporte ; chaque
  changement est tracé.
- **Choix d'une activité au moment d'un scan** : le scan porte soit l'ancienne, soit la nouvelle activité, jamais un
  mélange ni une activité non associée à la ligne.
- **Lecteur passé du mode "production" au mode "enregistrement"** : il perd son activité en cours ; ses
  associations sont conservées pour le jour où il repasse en production.
- **Lecteur désactivé** : son activité en cours et ses associations sont conservées ; le kiosque est refusé comme
  aujourd'hui pour un lecteur désactivé.
- **Scan autour de minuit** : une lecture faite avant minuit (heure de la station) porte l'activité de la veille,
  une lecture faite après n'en porte aucune tant que l'Opérateur n'a pas choisi ; jamais l'activité de la veille
  après minuit, même si le serveur a redémarré entre-temps.
- **Poste de nuit qui traverse minuit** : la ligne repasse à "aucune activité" à minuit et l'Opérateur doit
  choisir de nouveau l'activité ; le kiosque affiche alors "aucune activité".
- **Lectures antérieures à la fonctionnalité** : elles n'ont pas d'activité et apparaissent comme "Sans activité".

## Requirements _(mandatory)_

### Functional Requirements

**Gestion des activités (Administrateur)**

- **FR-001** : le système DOIT permettre à un Administrateur de créer une activité avec un nom obligatoire, non vide
  une fois les espaces autour retirés, d'au plus 100 caractères, et unique sans tenir compte de la casse ni des
  espaces autour.
- **FR-002** : le système DOIT permettre à un Administrateur de renommer une activité (mêmes règles que FR-001), de la
  désactiver et de la réactiver.
- **FR-003** : le système DOIT permettre à un Administrateur de supprimer une activité qu'aucune lecture ne porte, et
  DOIT refuser la suppression d'une activité portée par au moins une lecture, avec un message invitant à la
  désactiver. Précisé lors de `/speckit-plan` (research R7) : la suppression est aussi refusée pour une activité
  qui a déjà été l'activité en cours d'une ligne, car l'historique des changements (FR-012) la cite.
- **FR-004** : le système DOIT lister les activités, actives et désactivées, avec les lignes auxquelles chacune est
  associée ; cette liste est consultable par les Administrateurs et les Opérateurs.

**Association aux lignes (Administrateur)**

- **FR-005** : le système DOIT permettre à un Administrateur d'associer une activité à une ou plusieurs lignes et de
  l'en dissocier ; une ligne peut avoir plusieurs activités associées, une activité plusieurs lignes.
- **FR-006** : seules les lignes (lecteurs en mode "production") PEUVENT recevoir une association ; une association à
  un lecteur en mode "enregistrement" DOIT être refusée.
- **FR-007** : dissocier d'une ligne son activité en cours, ou désactiver une activité en cours sur des lignes, DOIT
  laisser ces lignes sans activité en cours ; le système DOIT indiquer à l'Administrateur, avant confirmation, les
  lignes concernées.

**Activité en cours (Opérateur)**

- **FR-008** : chaque ligne DOIT avoir au plus une activité en cours, choisie parmi ses activités associées et
  actives, ou aucune.
- **FR-008a** (Clarifications 2026-09-26) : chaque jour à minuit, dans le fuseau de la station (celui du tableau de
  bord, spec `007`, FR-007), le système DOIT remettre toutes les lignes à "aucune activité" ; les lectures suivantes
  n'en portent pas jusqu'au choix de l'Opérateur. Cette remise à zéro est tracée comme un changement (FR-012), avec
  pour auteur le système. Elle DOIT avoir lieu même si le serveur était arrêté à minuit : une activité choisie un jour
  précédent (dans le fuseau de la station) n'est jamais appliquée à une lecture du jour.
- **FR-009** : le kiosque d'une ligne DOIT afficher l'activité en cours de la ligne et permettre de la changer ou de
  la retirer, avec le jeton du lecteur de la ligne (spec `008`, FR-005a) ; ce jeton NE DOIT permettre de changer que
  l'activité en cours de sa propre ligne.
- **FR-009a** (Clarifications 2026-09-26) : tant que la ligne n'a pas d'activité en cours, le kiosque DOIT afficher en
  haut de l'écran un bandeau d'alerte, en couleur d'avertissement et visible en permanence. Ce bandeau indique qu'aucune
  activité n'est choisie et donne le nombre de lectures de la ligne faites sans activité depuis minuit, heure de la
  station. Il disparaît dès qu'une activité est choisie. Il NE DOIT PAS bloquer la consultation des lectures ni la
  revue de conformité.
- **FR-010** : un utilisateur connecté de rôle Opérateur ou Administrateur DOIT pouvoir consulter et changer
  l'activité en cours de n'importe quelle ligne, aux mêmes conditions.
- **FR-011** : le choix d'une activité non associée à la ligne, désactivée ou inexistante DOIT être refusé sans
  modifier l'activité en cours.
- **FR-012** : chaque changement d'activité en cours DOIT être tracé : ligne, activité précédente, nouvelle activité,
  date, et auteur (l'utilisateur connecté, le lecteur dont le jeton a servi au kiosque, comme pour les
  modifications de conformité de la spec `005`, ou le système pour la remise à zéro quotidienne, FR-008a).
- **FR-013** : un lecteur passé en mode "enregistrement" DOIT perdre son activité en cours ; ses associations sont
  conservées.

**Lectures**

- **FR-014** : chaque lecture de production créée DOIT porter l'activité en cours de sa ligne au moment du scan, ou
  aucune activité si la ligne n'en a pas (Clarifications 2026-09-26).
- **FR-015** : l'activité d'une lecture NE DOIT jamais changer après sa création, quels que soient les changements
  d'activité en cours, d'association ou d'état de l'activité ; un scan ignoré comme doublon (spec `004`, FR-008) ne
  la modifie pas. Aucune correction après coup n'est offerte, à aucun rôle (Clarifications 2026-09-26) : une lecture
  faite sous une mauvaise activité, parce que l'Opérateur a oublié d'en changer, la garde.
- **FR-016** : un scan NE DOIT jamais être refusé ni retardé faute d'activité en cours ; le format de la requête et de
  la réponse de scan du lecteur NE DOIT PAS changer (aucune reconfiguration des lecteurs).
- **FR-017** : les listes de lectures consultées au kiosque et par les utilisateurs connectés (spec `005`) DOIVENT
  indiquer l'activité de chaque lecture.

### Key Entities

- **Activité** (nouvelle) : un type de produit en production. Nom unique (FR-001), état actif ou désactivé, dates de
  création et de modification.
- **Association activité–ligne** (nouvelle) : indique qu'une ligne peut traiter une activité. Au plus une par couple
  ligne–activité.
- **Activité en cours d'une ligne** (nouvelle, portée par la ligne) : au plus une activité associée et active, ou
  aucune.
- **Changement d'activité en cours** (nouveau) : trace d'un changement — ligne, activité précédente et nouvelle (l'une
  ou l'autre peut être "aucune"), date, auteur (utilisateur, lecteur, ou système pour la remise à zéro quotidienne).
- **Lecteur / ligne** (spec `002`, modifié) : un lecteur en mode "production" gagne ses associations et son activité
  en cours.
- **Lecture** (spec `004`, modifiée) : porte en plus l'activité en cours au moment du scan, ou aucune.

## Success Criteria _(mandatory)_

### Measurable Outcomes

- **SC-001** : au kiosque, l'Opérateur change l'activité en cours de sa ligne en 2 gestes au plus (ouvrir le choix,
  choisir l'activité), en moins de 10 secondes.
- **SC-002** : 100 % des lectures faites après un changement d'activité portent la nouvelle activité, et 100 % des
  lectures faites avant gardent l'ancienne — vérifié par tests automatisés.
- **SC-003** : 0 scan refusé ou perdu à cause de l'activité (absente, dissociée, désactivée) ; les tests existants du
  scan passent sans modification de leur requête ni de la réponse attendue.
- **SC-004** : 100 % des tentatives, au kiosque, de choisir une activité non associée à sa ligne ou de changer
  l'activité en cours d'une autre ligne sont refusées — vérifié par tests automatisés, comme les autres routes de la matrice d'accès (spec
  `008`, SC-001).
- **SC-004a** : sur le kiosque d'une ligne sans activité en cours, le bandeau d'alerte est visible sans faire défiler
  l'écran, et le nombre de lectures sans activité qu'il affiche est exact (vérifié sur un jeu de lectures connu).
- **SC-005** : pour tout changement d'activité en cours, on retrouve qui l'a fait et quand.
- **SC-006** : un Administrateur met en place les activités d'une nouvelle saison (création et associations pour 3
  lignes et 5 activités) en moins de 5 minutes.

## Assumptions

- "Ligne" et "lecteur en mode production" désignent la même chose : il n'existe pas d'entité ligne distincte du
  lecteur, et cette fonctionnalité n'en crée pas.
- L'Opérateur de la ligne agit principalement depuis le kiosque tactile de la ligne, authentifié par le jeton du
  lecteur (spec `008`) ; les utilisateurs connectés peuvent aussi changer l'activité d'une ligne, pour dépanner.
- Une activité est un simple libellé ; aucun autre attribut (variété, calibre, client, objectif de cadence) n'est
  introduit. Le produit n'a pas d'effet sur la conformité, le cueilleur ou le seau.
- Aucune activité n'est choisie automatiquement : même si une ligne n'a qu'une activité associée, l'Opérateur la
  choisit explicitement, y compris chaque matin après la remise à zéro (FR-008a).
- Les lectures créées avant la livraison restent sans activité ; aucune reprise de données n'est prévue.
- Les lecteurs physiques ne changent pas : l'activité est déterminée côté serveur, pas envoyée par le lecteur.
- Hors périmètre (Clarifications 2026-09-26) : le filtre et les totaux par activité du tableau de bord (spec `007`)
  et de son export CSV ; le tableau de bord reste inchangé et compte toutes les lectures, avec ou sans activité.
- Volumétrie : quelques lignes, quelques dizaines d'activités au plus, quelques changements par ligne et par jour.
