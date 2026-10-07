# Feature Specification: Heures de travail des cueilleurs et caisses par activité

**Feature Branch**: `014-heures-travail-cueilleurs`

**Created**: 2026-10-07

**Status**: Delivered (2026-10-07)

**Input**: User description: "J'aimerais ajouter la notion de quantité d'heures de travail des cueilleurs afin de sortir une statistique sur le nombre de caisses traitées à l'heure."

## Contexte

Chaque caisse (seau) présentée au lecteur d'une ligne crée une lecture, attribuée au cueilleur affecté au seau au
moment du scan (specs `004` et `007`, FR-009), et qui porte l'activité en cours sur la ligne à ce moment-là (spec
`012`). Le tableau de bord (spec `007`) compte les lectures par cueilleur sur une période, mais l'application ne sait
pas combien de temps chaque cueilleur a travaillé, et ne découpe pas ses caisses par activité.

Cette fonctionnalité introduit les **heures de travail** d'un cueilleur et ajoute au tableau par cueilleur du tableau
de bord, pour la période choisie : ses heures réalisées et ses caisses par activité. Rapprocher les deux (caisses
traitées à l'heure) se fait à la lecture du tableau ou de son export ; aucune colonne calculée n'est ajoutée.

Tableau visé (exemple) :

| Cueilleur     | Heures | Fraise | Framboise | Sans activité | Total caisses |
|---------------|-------:|-------:|----------:|--------------:|--------------:|
| Dupont Marie  |   40,0 |    220 |        60 |             0 |           280 |
| Martin Paul   |   35,0 |    150 |        60 |            11 |           221 |
| Non attribué  |      — |      5 |         0 |             2 |             7 |
| **Total**     |   75,0 |    375 |       120 |            13 |           508 |

## Clarifications

### Session 2026-10-07

- Q: Comment les heures de travail d'un cueilleur sont-elles connues ? → A: Renseignées en début de journée par
  l'Administrateur : une durée par cueilleur et par jour, corrigeable ensuite si la journée réelle diffère.
- Q: Qui peut saisir les heures ? → A: L'Administrateur seulement.
- Q: Quel est le résultat attendu ? → A: Un tableau par cueilleur donnant, sur la période choisie, le nombre
  d'heures réalisées et le nombre de caisses par activité.
- Q: Les heures sont-elles saisies par ligne ? → A: Non, par cueilleur et par jour seulement : le besoin est par
  cueilleur, pas par ligne (remplace la réponse précédente sur le filtre par ligne).
- Q: Faut-il une colonne « caisses à l'heure » calculée ? → A: Non : le tableau donne les heures et les caisses par
  activité, sans colonne de caisses à l'heure.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Saisir les heures de travail des cueilleurs (Priority: P1)

En début de journée, l'Administrateur indique, pour chaque cueilleur qui travaille ce jour-là, le nombre d'heures de
travail prévu, pour toute l'équipe en une seule saisie. Si la journée réelle diffère (départ anticipé, absence,
heures en plus), il corrige la valeur le jour même ou un jour suivant.

**Why this priority**: les heures de travail sont la donnée manquante de la fonctionnalité ; sans elles, le tableau
ne peut pas mettre en regard le temps travaillé et les caisses traitées.

**Independent Test**: saisir 7,5 h pour un cueilleur le jour même, puis relire ses heures de ce jour : 7,5 h ; les
corriger en 8 h : la relecture donne 8 h.

**Acceptance Scenarios**:

1. **Given** un cueilleur sans heures aujourd'hui, **When** l'Administrateur saisit 7,5 h pour aujourd'hui,
   **Then** les heures sont enregistrées et visibles pour ce cueilleur et ce jour.
2. **Given** 7,5 h saisies pour un cueilleur un jour donné, **When** l'Administrateur les remplace par 8 h, **Then**
   seule la valeur 8 h est retenue pour ce jour (pas de cumul).
3. **Given** l'équipe du jour (plusieurs cueilleurs), **When** l'Administrateur renseigne le matin leurs heures en
   une seule saisie, **Then** chacun reçoit ses heures pour le jour même.
4. **Given** un Administrateur, **When** il saisit une durée nulle, négative, supérieure à 24 h, qui n'est pas un
   multiple d'un quart d'heure, ou pour une date future, **Then** la saisie est refusée avec un message explicite.
5. **Given** des heures saisies par erreur, **When** l'Administrateur les supprime, **Then** le cueilleur n'a plus
   d'heures ce jour-là.
6. **Given** un Opérateur connecté ou le kiosque d'une ligne, **When** il tente de saisir, corriger ou supprimer des
   heures, **Then** la demande est refusée.

---

### User Story 2 - Voir, par cueilleur, ses heures et ses caisses par activité (Priority: P1)

Sur le tableau de bord, pour la période choisie, le tableau par cueilleur affiche pour chaque cueilleur ses heures
réalisées, ses caisses dans une colonne par activité et son total de caisses ; une ligne Total donne les mêmes
chiffres pour tous les cueilleurs.

**Why this priority**: c'est le résultat demandé ; il ne vaut qu'avec la saisie (US1), les deux forment ensemble le
premier livrable utile.

**Independent Test**: avec, sur un jour, 7,5 h pour un cueilleur et 30 lectures attribuées à lui (20 en activité
Fraise, 10 en Framboise), le tableau de bord sur ce jour affiche pour lui 7,5 h, 20 Fraise, 10 Framboise et 30 au
total.

**Acceptance Scenarios**:

1. **Given** un cueilleur avec 7,5 h et 30 lectures (20 Fraise, 10 Framboise) le même jour, **When** on affiche le
   tableau de bord sur ce jour, **Then** sa ligne indique 7,5 h, 20 en Fraise, 10 en Framboise et 30 caisses.
2. **Given** un cueilleur avec 4 h lundi et 8 h mardi, **When** on affiche la période lundi–mardi, **Then** sa ligne
   indique 12 h.
3. **Given** des lectures faites sur une ligne sans activité en cours (spec `012`), **When** on affiche le tableau,
   **Then** elles apparaissent dans une colonne « Sans activité » et comptent dans le total de caisses.
4. **Given** une activité sans aucune lecture sur la période, **When** on affiche le tableau, **Then** elle n'a pas
   de colonne.
5. **Given** un cueilleur avec des lectures mais aucune heure sur la période, **When** on affiche le tableau,
   **Then** ses heures apparaissent « non saisies » (pas 0) et ses caisses restent comptées.
6. **Given** un cueilleur avec des heures mais aucune lecture sur la période, **When** on affiche le tableau,
   **Then** il apparaît avec ses heures et 0 caisse.
7. **Given** des chiffres affichés, **When** on exporte le CSV du tableau par cueilleur, **Then** il contient la
   colonne heures, une colonne par activité, le total caisses et la ligne Total.

---

### User Story 3 - Repérer les heures manquantes (Priority: P2)

L'Administrateur voit, pour une journée, tous les cueilleurs avec leurs caisses et leurs heures de ce jour-là, et
repère ceux qui ont des lectures sans heures pour les compléter.

**Why this priority**: évite des heures sous-estimées par des oublis de saisie, mais le tableau fonctionne sans.

**Independent Test**: un jour où 3 cueilleurs ont des lectures et un seul a des heures, la liste du jour montre les
3 cueilleurs et en signale 2 sans heures.

**Acceptance Scenarios**:

1. **Given** 3 cueilleurs avec des lectures le 2026-10-06 dont 1 seul avec des heures, **When** l'Administrateur
   ouvre la journée du 2026-10-06, **Then** les 3 cueilleurs sont listés et les 2 sans heures sont signalés.
2. **Given** un cueilleur avec des heures mais sans lecture ce jour-là, **When** l'Administrateur ouvre la journée,
   **Then** il est listé aussi, avec 0 caisse.

---

### Edge Cases

- **Lectures « Non attribué »** (spec `007`, FR-009) : elles n'ont pas de cueilleur, donc pas d'heures ; elles
  apparaissent dans la ligne « Non attribué » avec leurs colonnes d'activité, sans heures, et comptent dans les
  colonnes de caisses de la ligne Total.
- **Filtre par lecteur (ligne)** : les heures ne sont pas saisies par ligne ; quand une ligne est choisie, le tableau
  affiche les caisses par activité de cette ligne mais pas la colonne heures, avec un message invitant à choisir
  « Tous les lecteurs » pour la voir.
- **Conformité** : une caisse non conforme reste une caisse traitée ; elle compte dans les colonnes d'activité et le
  total (la conformité reste affichée à part, spec `007`).
- **Activité désactivée ou renommée** (spec `012`) : ses caisses restent dans sa colonne, sous son nom actuel.
- **Jour et fuseau** : le jour d'une saisie d'heures est le jour de la station (fuseau de la spec `007`, FR-007) ;
  une période « Aujourd'hui » compte les heures du jour de la station.
- **Poste de nuit qui traverse minuit** : les heures sont saisies sur le jour où elles ont été faites ; un poste de
  22 h à 6 h donne 2 h la veille et 6 h le lendemain.
- **Journée en cours** : les heures saisies le matin couvrent toute la journée prévue, alors que les caisses du jour
  s'accumulent jusqu'au soir ; la page le signale quand la période inclut le jour en cours.
- **Heures prévues différentes des heures réalisées** : l'Administrateur corrige la saisie ; le tableau utilise
  toujours la dernière valeur, visible au rafraîchissement suivant.
- **Cueilleur supprimé** (spec `001`) : ses heures sont supprimées avec lui et ne comptent plus dans aucun total.

## Requirements *(mandatory)*

### Functional Requirements

**Saisie des heures (Administrateur)**

- **FR-001** : le système DOIT permettre d'enregistrer, pour un cueilleur et un jour de la station, un nombre
  d'heures de travail strictement positif, d'au plus 24, par pas d'un quart d'heure.
- **FR-002** : un cueilleur DOIT avoir au plus une saisie d'heures par jour, sans distinction de ligne ni
  d'activité ; une nouvelle saisie pour le même jour remplace la précédente.
- **FR-003** : le système DOIT permettre de saisir les heures du jour même, de les corriger et de les supprimer le
  jour même ou un jour suivant, et de saisir après coup celles d'un jour passé oublié. Une saisie pour un jour futur
  DOIT être refusée.
- **FR-004** : le système DOIT permettre de saisir en une fois les heures de plusieurs cueilleurs pour un même jour.
- **FR-005** : le système DOIT conserver l'auteur et la date de la dernière modification de chaque saisie d'heures.
- **FR-006** : la création, la correction et la suppression d'heures DOIVENT être réservées à l'Administrateur ; un
  Opérateur et le kiosque d'une ligne DOIVENT être refusés. La consultation du tableau de bord, heures comprises,
  reste ouverte aux Opérateurs et Administrateurs (spec `007`, FR-004).

**Tableau par cueilleur (tableau de bord)**

- **FR-007** : le tableau par cueilleur du tableau de bord (spec `007`, FR-005b) DOIT afficher, pour chaque
  cueilleur et pour la période choisie : ses heures réalisées (somme de ses heures sur la période), ses caisses dans
  une colonne par activité et son total de caisses. Les colonnes existantes (non conformes, taux de conformité) sont
  conservées. Aucune colonne de caisses à l'heure n'est affichée.
- **FR-008** : les colonnes d'activité DOIVENT être celles des activités portées par au moins une lecture de la
  période (et du lecteur choisi), plus une colonne « Sans activité » si des lectures n'en portent pas. Une caisse
  compte dans l'activité portée par sa lecture (spec `012`), pas dans l'activité actuelle de la ligne.
- **FR-009** : un cueilleur sans aucune heure sur la période DOIT afficher ses heures « non saisies » ; un cueilleur
  avec des heures mais sans lecture sur la période DOIT apparaître avec 0 caisse.
- **FR-010** : la ligne Total DOIT donner la somme des heures, la somme de chaque colonne d'activité et le total des
  caisses ; la somme des lignes DOIT égaler la ligne Total pour chaque colonne, et le total des caisses DOIT égaler
  la synthèse du tableau de bord (spec `007`, FR-009).
- **FR-011** : quand un lecteur est choisi, la colonne heures NE DOIT PAS être affichée, les heures n'étant pas
  saisies par ligne ; les colonnes d'activité le sont, limitées à ce lecteur.
- **FR-012** : les heures et les caisses par activité DOIVENT être calculées par le serveur, comme les autres totaux
  du tableau de bord (spec `007`, FR-002), avec le même filtre de période.
- **FR-013** : l'export CSV du tableau par cueilleur (spec `007`, FR-005e) DOIT reprendre les mêmes colonnes et la
  ligne Total.

**Suivi des saisies**

- **FR-014** : le système DOIT permettre à l'Administrateur de lister, pour un jour donné, tous les cueilleurs (pour
  que la saisie du matin soit possible avant la première lecture), avec leur nombre de caisses et leurs heures de ce
  jour-là, en signalant ceux qui ont des lectures sans heures.

### Key Entities

- **Heures de travail** (nouvelle) : durée travaillée par un cueilleur un jour de la station. Attributs : cueilleur,
  jour, nombre d'heures, auteur (un Administrateur) et date de la dernière modification. Unique par cueilleur et
  jour ; ni ligne ni activité.
- **Cueilleur** (spec `001`) : inchangé ; porte ses heures de travail.
- **Lecture** (spec `004`) : inchangée ; une lecture attribuée à un cueilleur compte pour une caisse traitée, dans
  l'activité qu'elle porte (spec `012`).
- **Activité** (spec `012`) : inchangée ; donne les colonnes du tableau.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** : l'Administrateur saisit les heures d'une journée pour 30 cueilleurs en moins de 5 minutes.
- **SC-002** : pour un jeu connu de lectures, d'activités et d'heures, les heures et caisses par activité affichées
  (par cueilleur et Total) sont exactes, y compris sur une période de plusieurs jours avec des jours sans heures.
- **SC-003** : le tableau de bord, nouvelles colonnes comprises, s'affiche toujours en moins de 1 s sur une période
  de 31 jours contenant 200 000 lectures (même seuil que la spec `007`, SC-002). **Vérifié le 2026-10-07** : PostgreSQL 14, 200 000 lectures
  sur 31 jours (3 activités, 40 cueilleurs, 1 240 saisies d'heures), 0,15 s avec et sans lecteur ; totaux exacts.
- **SC-004** : 100 % des cueilleurs ayant des lectures sans heures sur une journée sont signalés dans la liste du
  jour.

## Assumptions

- Une **caisse traitée** est une lecture attribuée au cueilleur (spec `007`, FR-009) : le seau présenté au lecteur de
  la ligne est la caisse. Aucune notion de poids ou de type de caisse n'est introduite.
- Les heures ne sont pas réparties par activité : le tableau donne les heures du cueilleur, tous produits confondus.
- Cette fonctionnalité réalise le découpage par activité du tableau par cueilleur reporté par la spec `012` ; un
  filtre par activité (ne montrer qu'une activité) reste hors périmètre.
- Les heures de travail servent à cette statistique, pas à la paie : pas d'export de paie, de pauses détaillées ni
  de calcul d'heures supplémentaires.
- La période du tableau de bord reste limitée à 31 jours (spec `007`, FR-007).
- Les heures sont renseignées le matin pour la journée ; leur absence n'empêche jamais un scan, et un oubli se
  rattrape plus tard (FR-003).
