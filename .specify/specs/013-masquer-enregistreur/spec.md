# Feature Specification: Masquer l'enregistreur dans les choix de ligne

**Feature Branch**: `013-masquer-enregistreur`

**Created**: 2026-09-28

**Status**: Delivered (2026-09-28)

**Input**: User description: "NE pas proposer la ligne \"enregistreur\" (lecteur en mode enregistrement) lors du login en tant qu'opérateur avec login/mdp (choix de la ligne sur reader.html). Ne pas proposer \"enregistreur\" dans le filtre lecteur du dashboard (revient sur la spec 007 FR-001 / research R6)."

## Contexte

Un lecteur est soit en mode "production" (une ligne : ses scans créent des lectures de conformité), soit en mode
"enregistrement" (l'enregistreur du poste de l'Administrateur : ses lectures servent seulement à enregistrer des
tags sur un seau, specs `003` et `011`). L'enregistreur n'est pas une ligne de production.

Pourtant, deux listes le proposent aujourd'hui comme une ligne :

- l'écran de choix de la ligne, affiché à l'utilisateur qui ouvre la page des lectures avec son identifiant et son
  mot de passe (le kiosque, lui, reçoit sa ligne du lanceur et n'affiche pas ce choix, spec `008`) ;
- le filtre "Lecteur" du tableau de bord (spec `007`, FR-001), qui liste tous les lecteurs et suffixe
  « (enregistrement) » ceux qui sont dans ce mode.

Un Opérateur peut donc ouvrir par erreur l'enregistreur au lieu de sa ligne, et le filtre du tableau de bord propose
un choix qui n'a pas de sens pour le suivi de la production. Cette fonctionnalité retire l'enregistreur de ces deux
listes. Pour le tableau de bord, elle remplace la règle de la spec `007` (FR-001, research R6) qui gardait ces
lecteurs dans le filtre pour consulter leurs lectures de production passées.

## User Scenarios & Testing _(mandatory)_

### User Story 1 - L'Opérateur ne voit que les lignes de production au choix de la ligne (Priority: P1)

Un Opérateur se connecte avec son identifiant et son mot de passe et arrive sur l'écran de choix de la ligne. Il n'y
voit que les lecteurs en mode "production" ; l'enregistreur n'y figure pas.

**Why this priority**: c'est le cas qui provoque des erreurs au quotidien : ouvrir l'enregistreur au lieu de sa ligne
ne montre aucune lecture de production.

**Independent Test**: avec deux lecteurs en production (L1, L2) et un en enregistrement (E), un Opérateur connecté
voit L1 et L2 à l'écran de choix, et pas E.

**Acceptance Scenarios**:

1. **Given** les lecteurs L1 et L2 en mode "production" et E en mode "enregistrement", **When** un Opérateur connecté
   ouvre l'écran de choix de la ligne, **Then** il voit L1 et L2, et pas E.
2. **Given** le lecteur E en mode "enregistrement", **When** l'Administrateur le repasse en mode "production",
   **Then** E apparaît à l'écran de choix de la ligne au prochain affichage de cet écran.
3. **Given** un lecteur L3 en mode "production" et désactivé, **When** un Opérateur ouvre l'écran de choix, **Then**
   L3 reste proposé, marqué « désactivé », comme aujourd'hui.
4. **Given** un Administrateur connecté, **When** il ouvre l'écran de choix de la ligne, **Then** il voit la même
   liste que l'Opérateur, sans l'enregistreur.

---

### User Story 2 - Le filtre "Lecteur" du tableau de bord ne propose pas l'enregistreur (Priority: P2)

Sur le tableau de bord, le filtre "Lecteur" ne liste que les lecteurs en mode "production", actifs ou désactivés.

**Why this priority**: gêne moindre (le tableau de bord est consulté, pas utilisé en poste), mais même confusion.

**Independent Test**: avec L1 en production et E en enregistrement, le filtre "Lecteur" du tableau de bord propose
"Tous les lecteurs" et L1, et pas E.

**Acceptance Scenarios**:

1. **Given** L1 en mode "production" et E en mode "enregistrement", **When** un utilisateur ouvre le tableau de bord,
   **Then** le filtre "Lecteur" propose "Tous les lecteurs" et L1 ; E n'y figure pas et aucune option n'est suffixée
   « (enregistrement) ».
2. **Given** un lecteur désactivé en mode "production", **When** on ouvre le tableau de bord, **Then** il reste
   proposé, suffixé « (désactivé) », comme aujourd'hui.
3. **Given** E, passé en mode "enregistrement" après avoir produit des lectures de production, **When** on consulte
   "Tous les lecteurs" sur une période qui contient ces lectures, **Then** elles restent comptées dans les chiffres
   et dans l'export CSV : seule l'option E disparaît du filtre.

### Edge Cases

- **Aucun lecteur en mode "production"** : l'écran de choix de la ligne indique qu'aucune ligne de production n'est
  configurée, même si un enregistreur existe ; le filtre du tableau de bord ne propose que "Tous les lecteurs".
- **Changement de mode pendant qu'une page est ouverte** : les listes reflètent le mode des lecteurs au moment où la
  page les charge ; une page déjà ouverte est à jour à son prochain chargement.
- **Lecteur passé en enregistrement alors qu'il est ouvert à la page des lectures** : comportement inchangé (spec
  `012` : l'activité n'est plus affichée) ; il n'est simplement plus proposé au prochain choix de ligne.
- **Kiosque d'un enregistreur** : inchangé ; le kiosque ne passe pas par l'écran de choix de la ligne.

## Requirements _(mandatory)_

### Functional Requirements

- **FR-001** : l'écran de choix de la ligne, affiché à un utilisateur connecté (Opérateur ou Administrateur), DOIT
  proposer uniquement les lecteurs en mode "production", actifs ou désactivés ; un lecteur en mode "enregistrement"
  NE DOIT PAS y être proposé.
- **FR-002** : le filtre "Lecteur" du tableau de bord DOIT proposer "Tous les lecteurs" puis uniquement les lecteurs
  en mode "production", actifs ou désactivés (suffixe « (désactivé) » conservé) ; un lecteur en mode "enregistrement"
  NE DOIT PAS y être proposé. Cette règle remplace, pour les lecteurs en enregistrement, la spec `007` FR-001
  (research R6).
- **FR-003** : le choix "Tous les lecteurs" du tableau de bord et son export CSV DOIVENT continuer de compter toutes
  les lectures de production de la période, y compris celles d'un lecteur aujourd'hui en mode "enregistrement".
- **FR-004** : le mode pris en compte DOIT être le mode actuel du lecteur, au chargement de la page : un lecteur
  repassé en mode "production" DOIT réapparaître dans les deux listes.
- **FR-005** : les droits d'accès ne changent pas : cette fonctionnalité ne retire l'enregistreur que des listes de
  choix, sans modifier ce qu'un utilisateur ou le kiosque est autorisé à consulter.

### Key Entities

- **Lecteur** (spec `002`, inchangé) : son mode ("production" ou "enregistrement") décide désormais s'il est proposé
  dans l'écran de choix de la ligne et dans le filtre du tableau de bord.

## Success Criteria _(mandatory)_

### Measurable Outcomes

- **SC-001** : dans 100 % des cas, un lecteur en mode "enregistrement" est absent de l'écran de choix de la ligne et
  du filtre "Lecteur" du tableau de bord, quel que soit le rôle de l'utilisateur connecté.
- **SC-002** : 100 % des lecteurs en mode "production" (actifs ou désactivés) restent proposés dans ces deux listes.
- **SC-003** : pour une même période, les chiffres de "Tous les lecteurs" au tableau de bord sont identiques avant et
  après la fonctionnalité.

## Assumptions

- "Ligne enregistreur" désigne tout lecteur en mode "enregistrement" (spec `002`) ; il peut y en avoir plusieurs.
- La demande vise l'Opérateur ; l'Administrateur voit le même écran de choix de la ligne et n'y a pas davantage
  besoin de l'enregistreur (il travaille avec celui-ci depuis la page d'enregistrement des tags). La règle s'applique
  donc aux deux rôles.
- Les lectures de production passées d'un lecteur aujourd'hui en enregistrement ne sont plus consultables isolément
  au tableau de bord ; elles restent dans "Tous les lecteurs". C'est le choix qui remplace la spec `007` research R6.
  Pour les consulter isolément, on repasse ce lecteur en production.
- Le changement porte seulement sur les listes proposées ; les services qui renvoient les lecteurs et les
  statistiques ne changent pas. Les autres pages qui listent les lecteurs (gestion des lecteurs, association des
  activités, enregistrement des tags) ne sont pas concernées.
