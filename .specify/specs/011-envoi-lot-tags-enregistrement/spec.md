# Feature Specification: Envoi groupé des tags par le lecteur d'enregistrement

**Feature Branch**: `011-envoi-lot-tags-enregistrement`

**Created**: 2026-09-26

**Status**: Delivered (2026-09-26)

**Input**: User description: "The "enregistreur" reader currently calls the same endpoint as the "production" readers (POST /api/tags/scan) and sends the tags to assign to a bucket one at a time. Add a separate endpoint that accepts a list of tags to assign in a single call."

## Contexte

Depuis la spec `003` (FR-007), un lecteur en mode "enregistrement" (l'enregistreur du poste de l'Administrateur)
envoie ses lectures au même point d'entrée que les lecteurs de production des lignes, un tag par appel. Le système
oriente chaque lecture selon le mode du lecteur : pour l'enregistreur, elle est gardée comme lecture temporaire de la
session d'enregistrement ouverte sur ce lecteur, et la page d'enregistrement l'affiche. L'Administrateur enregistre
ensuite ces tags sur un seau, avec les confirmations prévues (tag déjà sur un autre seau, spec `003` FR-009 ; tag hors
liste, spec `010`).

Un seau porte plusieurs tags, que l'enregistreur lit ensemble. Les envoyer un par un multiplie les appels, fait
arriver les tags au fil de l'eau sur la page, et laisse la session à moitié remplie si la liaison réseau coupe en
cours d'envoi. Cette fonctionnalité donne à l'enregistreur un point d'entrée qui lui est propre et qui accepte en un
seul appel la liste des tags lus.

## Clarifications

### Session 2026-09-26

- Q: Quand l'enregistreur envoie sa liste de tags, ces tags alimentent-ils seulement la session ouverte sur la page, ou sont-ils associés directement à un seau ? → A: Ils alimentent seulement la session ouverte ; l'Administrateur saisit ensuite le numéro de seau et enregistre sur la page, avec les confirmations existantes.
- Q: Que peut envoyer l'enregistreur en un appel : une liste dont nous définissons le format, ou un format imposé par le fabricant du lecteur ? → A: Nous le définissons : une simple liste d'UID de tags, sans autre information par tag.
- Q: Si l'enregistreur envoie sa liste alors qu'aucune session n'est ouverte, que deviennent ces tags ? → A: Ils sont ignorés, comme aujourd'hui ; l'appel réussit et la réponse indique qu'aucune session n'est ouverte. Rien n'est gardé en attente d'un futur "Démarrer".

## User Scenarios & Testing _(mandatory)_

### User Story 1 - L'enregistreur envoie tous les tags lus en un seul appel (Priority: P1)

L'Administrateur a démarré une session d'enregistrement sur l'enregistreur de son poste. Il présente un seau au
lecteur ; le lecteur envoie en un seul appel la liste des tags qu'il a lus. Tous ces tags apparaissent sur la page
d'enregistrement, qui les traite exactement comme s'ils étaient arrivés un par un (seau actuel de chaque tag, tag hors
liste, limite de 100 tags). L'Administrateur saisit le numéro de seau et enregistre, sans changement de son côté.

**Why this priority**: c'est l'objet même de la demande ; sans elle, rien ne change pour l'enregistreur.

**Independent Test**: avec une session ouverte sur un enregistreur, un appel portant 5 tags distincts fait apparaître
ces 5 tags dans la session ; l'enregistrement du seau les associe comme aujourd'hui.

**Acceptance Scenarios**:

1. **Given** une session d'enregistrement ouverte sur l'enregistreur, **When** celui-ci envoie une liste de 5 tags
   distincts, **Then** la session contient ces 5 tags, et la réponse indique que 5 tags ont été reçus et 5 ajoutés à la
   session.
2. **Given** une session qui contient déjà le tag A, **When** l'enregistreur envoie la liste A, B, B, **Then** la
   session contient A et B, une seule fois chacun, et la réponse indique 2 tags distincts reçus dont 1 ajouté.
3. **Given** aucune session ouverte sur l'enregistreur (ou une session expirée), **When** il envoie une liste de tags,
   **Then** aucun tag n'est gardé et la réponse l'indique, sans erreur, comme le fait aujourd'hui l'envoi d'un tag seul.
4. **Given** une liste dont certains éléments sont vides ou ne contiennent que des espaces, **When** elle est envoyée,
   **Then** ces éléments sont ignorés, les autres tags sont gardés, et les espaces autour des UID sont retirés.
5. **Given** une liste de tags gardée par un seul appel, **When** l'Administrateur enregistre la session sur un seau,
   **Then** le résultat (association, confirmations de déplacement et de tag hors liste, limite de 100) est le même
   que si ces tags avaient été envoyés un par un.

---

### User Story 2 - Seul l'enregistreur peut utiliser ce point d'entrée (Priority: P2)

Un lecteur de production qui appellerait, par erreur de configuration, le point d'entrée d'envoi groupé ne doit ni
créer de lectures de conformité ni alimenter une session d'enregistrement.

**Why this priority**: une confusion entre les deux points d'entrée fausserait soit les comptages de conformité, soit
un enregistrement de seau ; il faut que l'erreur soit visible immédiatement lors de l'installation du lecteur.

**Independent Test**: un lecteur en mode production qui appelle le point d'entrée groupé reçoit un refus, et aucune
donnée n'est créée.

**Acceptance Scenarios**:

1. **Given** un lecteur actif en mode "production", **When** il appelle le point d'entrée d'envoi groupé, **Then**
   l'appel est refusé avec un message indiquant que ce point d'entrée est réservé aux lecteurs en mode
   "enregistrement", et aucune lecture n'est créée.
2. **Given** un appel sans jeton de lecteur, avec un jeton inconnu ou le jeton d'un lecteur désactivé, **When** il
   atteint le point d'entrée d'envoi groupé, **Then** il est refusé comme l'est aujourd'hui un scan dans ces cas.
3. **Given** un utilisateur connecté au front (Administrateur ou Opérateur), **When** il appelle le point d'entrée
   d'envoi groupé avec sa session, **Then** l'appel est refusé : seul un lecteur peut l'utiliser.

---

### User Story 3 - L'envoi tag par tag reste possible pendant la migration (Priority: P3)

Tant que l'enregistreur n'est pas reconfiguré, il continue d'envoyer ses tags un par un au point d'entrée actuel, et
cela fonctionne comme aujourd'hui.

**Why this priority**: permet de livrer le backend sans coordonner le jour même la reconfiguration du lecteur ; aucune
régression pour les lecteurs de production.

**Independent Test**: les tests existants de l'envoi tag par tag d'un enregistreur et des scans de production passent
sans modification.

**Acceptance Scenarios**:

1. **Given** un enregistreur non reconfiguré et une session ouverte, **When** il envoie un tag au point d'entrée
   actuel, **Then** le tag est gardé dans la session, comme aujourd'hui.
2. **Given** un lecteur de production, **When** il envoie un scan au point d'entrée actuel, **Then** le comportement
   (lecture de conformité, doublons, tag hors liste) est inchangé.

### Edge Cases

- **Liste vide, absente, ou ne contenant que des éléments vides** : l'appel est refusé comme requête invalide ; rien
  n'est gardé et la session n'est pas prolongée.
- **Plus de 100 tags distincts dans un appel** : l'appel est refusé comme requête invalide sans rien garder, puisque
  la session ne pourrait de toute façon pas être enregistrée au-delà de 100 tags (spec `003`, FR-011).
- **Session qui dépasserait 100 tags avec cet appel** : les tags sont gardés comme aujourd'hui ; c'est
  l'enregistrement du seau qui est refusé et la page qui le signale (spec `003`, FR-011), comme pour des tags envoyés
  un par un.
- **Même tag répété dans la liste, ou déjà dans la session** : gardé une seule fois (règle actuelle des lectures
  temporaires).
- **Envoi du même lot deux fois** (le lecteur renvoie après une coupure réseau) : le second envoi n'ajoute rien ; la
  réponse indique 0 tag ajouté.
- **Deux appels simultanés du même enregistreur avec des tags communs** : chaque tag n'est gardé qu'une fois et aucun
  des deux appels n'échoue pour cette raison : le second attend que le premier soit terminé.
- **Envoi groupé en concurrence avec un envoi tag par tag du même tag, ou session bloquée trop longtemps** (précisé
  après `/speckit-analyze`) : le système réessaie une fois ; si le conflit se reproduit, l'appel est refusé comme
  conflit, rien n'est gardé, et le lecteur peut renvoyer la liste. L'appel n'aboutit jamais à une erreur interne.
- **Session annulée ou enregistrée pendant un appel** : les tags de cet appel ne sont pas rattachés au seau qui vient
  d'être enregistré ; au pire ils sont ignorés comme s'il n'y avait pas de session.
- **Tag hors liste de référence ou déjà associé à un autre seau** : gardé dans la session comme les autres ; les
  confirmations se font à l'enregistrement du seau (specs `003` et `010`), pas à l'envoi.
- **Enregistreur en mode "enregistrement" mais désactivé** : refusé comme tout lecteur désactivé.

## Requirements _(mandatory)_

### Functional Requirements

- **FR-001** : le système DOIT offrir aux lecteurs un point d'entrée d'envoi groupé, distinct du point d'entrée de
  scan actuel, qui accepte en un seul appel une simple liste d'UID de tags, sans autre information par tag (ni
  indicateur de conformité, ni date ou nombre de lectures) ; ce format est défini par cette fonctionnalité
  (Clarifications 2026-09-26).
- **FR-002** : ce point d'entrée DOIT exiger l'authentification du lecteur par son propre jeton, comme le scan actuel,
  et DOIT refuser tout appel sans jeton valide de lecteur actif, y compris un appel porté par une session
  d'utilisateur du front.
- **FR-003** : ce point d'entrée DOIT être réservé aux lecteurs en mode "enregistrement". L'appel d'un lecteur en mode
  "production" DOIT être refusé avec un message explicite, sans créer ni lecture de conformité ni lecture temporaire.
- **FR-004** : pour un enregistreur dont une session d'enregistrement est ouverte et non expirée, le système DOIT
  garder chaque UID distinct et non vide de la liste comme lecture temporaire de cette session, selon les mêmes règles
  qu'un tag envoyé seul : espaces autour de l'UID retirés, un tag déjà présent dans la session n'est pas ajouté une
  seconde fois, aucun tag, seau ni lecture de conformité n'est créé.
- **FR-005** : un appel accepté DOIT prolonger la session (dernière activité) une seule fois, comme le fait une
  lecture isolée.
- **FR-006** : si aucune session n'est ouverte sur l'enregistreur, ou si elle a expiré, le système NE DOIT garder
  aucun tag de la liste et DOIT répondre avec succès en indiquant qu'aucune session n'est ouverte, comme pour un tag
  envoyé seul (une session expirée est alors fermée). Aucun tag n'est gardé en attente : un "Démarrer" ultérieur ne
  reprend pas une liste reçue avant lui ; il faut présenter le seau à nouveau (Clarifications 2026-09-26).
- **FR-007** : la réponse DOIT indiquer au lecteur, pour l'appel : si une session était ouverte, le nombre d'UID
  distincts non vides reçus, et le nombre de tags nouvellement ajoutés à la session.
- **FR-008** : le système DOIT refuser comme requête invalide, sans rien garder ni prolonger la session, une liste
  absente, vide, ne contenant que des éléments vides, ou comptant plus de 100 UID distincts non vides. Précisé
  lors de `/speckit-plan` (research R4) : aussi une liste de plus de 1 000 éléments (répétitions comprises), ou un UID
  de plus de 50 caractères une fois les espaces retirés.
- **FR-009** : les éléments vides ou composés uniquement d'espaces DOIVENT être ignorés quand la liste contient par
  ailleurs au moins un UID valide.
- **FR-010** : les tags d'un appel DOIVENT être gardés dans la session tous ensemble ou pas du tout : la page
  d'enregistrement ne doit jamais afficher une partie seulement d'un appel accepté.
- **FR-011** : le point d'entrée de scan actuel DOIT continuer à fonctionner sans changement, pour les lecteurs de
  production comme pour les enregistreurs qui envoient encore leurs tags un par un.
- **FR-012** : l'enregistrement du seau à partir de la session (confirmations de déplacement et de tag hors liste,
  limite de 100 tags) DOIT rester inchangé, quelle que soit la façon dont les tags ont été envoyés.
- **FR-013** (Clarifications 2026-09-26) : un envoi groupé NE DOIT associer aucun tag à un seau et ne porte aucun
  numéro de seau ; il alimente uniquement la session d'enregistrement ouverte. L'association se fait seulement quand
  l'Administrateur enregistre la session sur la page.

### Key Entities

- **Lot de tags** (nouveau, non conservé) : ce qu'envoie l'enregistreur en un appel — une liste d'UID, rien d'autre. Il n'est pas
  stocké en tant que tel ; seuls ses tags deviennent des lectures temporaires.
- **Lecture temporaire** (spec `003`, inchangée) : un tag vu par un enregistreur pendant une session ; au plus une par
  tag et par session.
- **Session d'enregistrement** (spec `003`, inchangée) : au plus une par enregistreur ; sa dernière activité est mise
  à jour par chaque appel accepté.
- **Lecteur** (spec `002`, inchangé) : son mode ("production" ou "enregistrement") décide s'il peut utiliser le point
  d'entrée d'envoi groupé.

## Success Criteria _(mandatory)_

### Measurable Outcomes

- **SC-001** : un seau de 100 tags est transmis par l'enregistreur en 1 appel au lieu de 100.
- **SC-002** : après un appel accepté, 100 % des tags distincts de la liste apparaissent sur la page d'enregistrement
  dès son rafraîchissement suivant, et jamais une partie seulement d'entre eux.
- **SC-003** : 0 lecture de conformité et 0 lecture temporaire créées par un appel d'un lecteur de production au point
  d'entrée d'envoi groupé ; l'installateur voit le motif du refus dans la réponse.
- **SC-004** : envoyer deux fois le même lot laisse la session identique à celle obtenue après un seul envoi.
- **SC-005** : les tests automatisés existants de l'envoi tag par tag (enregistreur et production) passent sans
  modification.
- **SC-006** : un appel de 100 tags distincts est accepté ; un appel de 101 tags distincts est refusé sans rien
  garder. Ces cas sont vérifiés par des tests automatisés.

## Assumptions

- Le lecteur physique de l'enregistreur peut être configuré pour envoyer une liste de tags au format défini par cette
  fonctionnalité (Clarifications 2026-09-26) ; sa reconfiguration est hors périmètre et se fait après la livraison du backend.
- L'envoi tag par tag des enregistreurs reste accepté, sans échéance de retrait dans cette fonctionnalité ; son
  retrait éventuel fera l'objet d'une décision distincte.
- La limite de 100 par appel reprend celle d'un enregistrement de seau (spec `003`, FR-011) ; elle porte sur les UID
  distincts non vides, pas sur le nombre brut d'éléments de la liste (un lecteur peut répéter un tag lu plusieurs fois).
- Aucune modification de la page d'enregistrement n'est nécessaire : elle affiche déjà les lectures de la session,
  quelle que soit leur origine.
- Volumétrie : un ou deux enregistreurs, quelques appels par minute ; aucune cible de latence particulière.
