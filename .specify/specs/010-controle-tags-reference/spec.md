# Feature Specification: Contrôle des tags par rapport à la liste de référence

**Feature Branch**: `010-controle-tags-reference`

**Created**: 2026-09-26

**Status**: Delivered (2026-09-26); comparison rule revised the same day after production feedback (FR-002: last 12 characters of the UID) and implemented (tasks T036–T040). Revised again on 2026-09-26: tags not in the list are dropped without being shown, and those already stored are deleted once (FR-003 to FR-009); implemented on 2026-09-26 (tasks T001–T038 of the revised tasks.md) — see Clarifications

**Input**: User description: "I would like to check and alert if a tag is not part of the list @doc/rfid_tag_list.csv in the app"

## Clarifications

### Session 2026-09-26

- Q: Comment la liste de référence est-elle mise à jour quand de nouveaux tags sont achetés ? → A: Liste figée livrée avec l'application ; on modifie le fichier et on déploie une nouvelle version.
- Q: Que se passe-t-il à l'enregistrement d'un seau dont des tags sont hors liste ? → A: ~~Avertir, puis enregistrer après confirmation~~ — remplacé par la session 2026-09-26 (révision) ci-dessous.
- Q: Que devient un scan de production d'un tag hors liste ? → A: ~~La lecture est créée comme aujourd'hui et marquée "tag hors liste"~~ — remplacé par la session 2026-09-26 (révision) ci-dessous.
- Q: Quand la confirmation montre un tag hors liste que l'Administrateur veut écarter, comment fait-il ? → A: ~~Il annule toute la session et recommence sans ce tag~~ — sans objet depuis la session 2026-09-26 (révision) : les tags hors liste n'entrent plus dans la session.
- Q: Un même tag lu une fois en minuscules et une fois en majuscules doit-il être traité comme un seul tag ? → A: La casse n'est ignorée que pour la comparaison à la liste ; les UID restent enregistrés tels que reçus, unifier leur casse est hors périmètre.
- Q: Sur quel écran le tag scanné aurait-il dû apparaître comme hors liste ? → A: L'écran de contrôle de la ligne (`reader.html`). Constaté en production le 2026-09-26, connecté en Opérateur : toutes les lectures de la ligne y sont affichées "hors liste", y compris celles de tags achetés, si bien qu'un tag étranger ne se distingue pas. *(Sans objet depuis la révision du 2026-09-26 : plus aucune indication "hors liste" n'est affichée.)*
- Q: À quoi ressemble l'UID d'un tag acheté tel que l'application le reçoit en production ? → A: `E2806915000040287477C993`, alors que la liste contient `E2806915200040287477C993` (ligne 1469) : seul le 9e caractère diffère (`0` au lieu de `2`). Les 5 008 lignes de la liste ont `2000` en positions 9 à 12 ; les lecteurs de production envoient `0000` à cet endroit.
- Q: Comment comparer les UID reçus des lecteurs à la liste, puisqu'ils diffèrent de ses lignes ? → A: Seuls les 12 derniers caractères de l'UID comptent. Toutes les lignes de la liste commencent par les mêmes 12 caractères (`E28069152000`) et leurs 12 derniers caractères sont tous différents : ils suffisent à identifier chaque tag acheté.

### Session 2026-09-26 (révision : tags hors liste écartés sans affichage)

Retour utilisateur : l'utilisateur ne veut ni voir ni conserver les tags hors liste ; la liste de référence sert
seulement à empêcher qu'un tag inconnu entre en base.

- Q: Quand un lecteur de ligne scanne un tag absent de la liste de référence, que reçoit-il ? → A: Une réponse `200` au format habituel, avec un message du type "Tag not in reference list, ignored" ; ni tag ni lecture ne sont créés (comme un scan en mode enregistrement sans session ouverte).
- Q: À l'enregistrement de tags sur un seau, que devient un tag hors liste ? → A: La lecture est écartée dès son arrivée dans la session (ni conservée ni affichée) ; un appel direct au service d'association contenant un UID hors liste est refusé en entier (`400`), rien n'est enregistré.
- Q: Que deviennent les tags hors liste déjà en base et la liste des tags hors liste de l'Administrateur ? → A: Les tags hors liste existants sont supprimés une fois, au déploiement, avec leurs lectures et leur association à un seau ; la liste des tags hors liste (ancienne User Story 3) et toute indication "hors liste" (page d'enregistrement, écran de contrôle de la ligne) sont retirées.
- Q: La suppression des tags hors liste a-t-elle lieu une seule fois, ou à chaque démarrage ? → A: Une seule fois, au déploiement de cette révision ; une modification ultérieure de la liste n'agit que sur les nouveaux scans et enregistrements, sans supprimer de données.

## Contexte

Le fichier `rfid_tag_list.csv` (fourni dans `doc/`, livré avec l'application sous `src/main/resources/tags/`) recense les 5 008 tags RFID achetés pour l'exploitation : un identifiant (UID) par
ligne, 24 caractères hexadécimaux majuscules, sans en-tête ni doublon. Aujourd'hui, l'application accepte n'importe
quel UID non vide : un tag inconnu scanné par un lecteur est créé à la volée (spec `004`, FR-002), et un Administrateur
peut enregistrer n'importe quel tag lu sur un seau (spec `003`). Un tag étranger (tag d'un autre site, badge, lecture
parasite, UID mal lu) passe donc inaperçu et peut fausser le comptage d'un cueilleur.

Cette fonctionnalité fait de cette liste la **liste de référence** des tags attendus et écarte, sans le montrer,
tout tag qui n'y figure pas (**tag hors liste**) : il n'entre jamais en base. Les tags hors liste déjà en base sont
supprimés une fois, au déploiement (révision du 2026-09-26).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Tags hors liste écartés à l'enregistrement sur un seau (Priority: P1)

Un Administrateur enregistre les tags d'un seau depuis la page d'enregistrement des tags. Les tags lus par le lecteur
du poste qui ne figurent pas dans la liste de référence sont écartés dès leur arrivée : ils n'apparaissent pas sur la
page et ne peuvent pas être enregistrés sur un seau.

**Why this priority**: c'est à l'enregistrement qu'un tag entre dans le suivi d'un seau ; l'arrêter là évite qu'un
tag étranger ne produise ensuite des lectures attribuées à un cueilleur. C'est aussi le parcours le plus simple à
livrer seul.

**Independent Test**: démarrer une session d'enregistrement, faire lire un tag de la liste et un tag hors liste, et
vérifier que seul le premier apparaît et est enregistré sur le seau.

**Acceptance Scenarios**:

1. **Given** une session d'enregistrement en cours, **When** le lecteur du poste lit un tag présent dans la liste de
   référence, **Then** le tag s'affiche comme aujourd'hui.
2. **Given** une session d'enregistrement en cours, **When** le lecteur du poste lit un tag absent de la liste de
   référence, **Then** la lecture n'est pas conservée dans la session et rien ne s'affiche ; le lecteur reçoit une
   réponse de même forme qu'aujourd'hui, dont le message indique la lecture ignorée (FR-005).
3. **Given** des tags lus dont un tag hors liste, **When** l'Administrateur enregistre le seau, **Then** seuls les tags
   de la liste sont enregistrés, sans demande de confirmation liée à la liste de référence.
4. **Given** un appel direct au service d'association (sans passer par la page), **When** la liste fournie contient au
   moins un tag hors liste, **Then** la requête est refusée (`400`) et rien n'est enregistré, même si l'appelant
   confirme par ailleurs un tag déjà associé à un autre seau.

---

### User Story 2 - Tags hors liste ignorés lors d'un scan en production (Priority: P2)

Un lecteur de ligne scanne un seau au passage. Si le tag lu n'est pas dans la liste de référence, le scan est ignoré :
aucune lecture n'est créée, rien ne s'affiche, et la ligne n'est ni ralentie ni interrompue.

**Why this priority**: un tag étranger scanné en production (tag d'un autre site, lecture parasite) ne doit ni
entrer en base ni fausser un comptage ; l'enregistrement (User Story 1) couvre déjà l'entrée normale des tags.

**Independent Test**: faire scanner par un lecteur de production un tag hors liste, puis vérifier que le lecteur
reçoit une réponse `200` et qu'aucun tag ni aucune lecture n'a été créé.

**Acceptance Scenarios**:

1. **Given** un lecteur en mode production, **When** il scanne un tag présent dans la liste de référence, **Then**
   le comportement actuel est inchangé (spec `004`).
2. **Given** un lecteur en mode production, **When** il scanne un tag hors liste, **Then** il reçoit une réponse `200`
   au format habituel avec un message indiquant que le tag est ignoré, et ni tag ni lecture ne sont créés ; rien
   n'apparaît sur l'écran de contrôle de la ligne.

---

### User Story 3 - Suppression des tags hors liste déjà en base (Priority: P3)

Des tags hors liste sont entrés en base avant cette révision (scans de production, enregistrements confirmés). Au
déploiement de la révision, ils sont supprimés avec tout ce qui s'y rattache, pour que la base ne contienne plus que
des tags de la liste de référence.

**Why this priority**: opération unique, au déploiement ; les deux premiers parcours empêchent tout nouveau cas.

**Independent Test**: avec une base contenant des tags dans la liste et hors liste (avec lectures, historique de
conformité et association à un seau), déployer la révision et vérifier que seuls les tags hors liste et ce qui s'y
rattache ont disparu.

**Acceptance Scenarios**:

1. **Given** des tags hors liste en base, avec leurs lectures et leur association à un seau, **When** la révision est
   déployée, **Then** ces tags, leurs lectures (et l'historique de conformité de ces lectures) et leur association à
   un seau sont supprimés ; le seau lui-même est conservé.
2. **Given** des tags de la liste en base, **When** la révision est déployée, **Then** ni eux, ni leurs lectures, ni
   leur association ne sont modifiés.
3. **Given** la sauvegarde faite avant chaque déploiement (spec `009`), **When** la suppression a eu lieu, **Then**
   les données supprimées restent récupérables depuis cette sauvegarde.

---

### Edge Cases

- **Casse et espaces** : un UID lu en minuscules ou entouré d'espaces est comparé à la liste après suppression des
  espaces en début et fin et passage en majuscules ; `e2806915...` et `E2806915...` sont tous deux reconnus comme dans
  la liste.
- **Début d'UID différent de la liste** : les lecteurs de production envoient `E28069150000…` là où la liste porte
  `E28069152000…`. Seuls les 12 derniers caractères étant comparés (FR-002), les deux formes désignent le même tag
  acheté. Conséquence acceptée : un UID d'une autre longueur ou d'un autre préfixe qui se termine par les 12 mêmes
  caractères qu'une ligne de la liste est reconnu comme dans la liste. L'enregistrement des UID en base reste inchangé (espaces retirés, casse conservée) : unifier la casse des
  tags est hors périmètre (research R3).
- **UID de format inattendu** : un UID de moins de 12 caractères, ou dont les 12 derniers caractères ne sont ceux
  d'aucune ligne de la liste, est hors liste ; aucun rejet supplémentaire n'est introduit (le rejet de l'UID vide, spec `004`, reste
  inchangé).
- **Liste de référence absente, vide ou mal formée** : la liste est livrée avec l'application (FR-001) ; une version
  dont la liste manque, est vide ou contient une ligne qui n'est pas un UID ne doit pas démarrer, plutôt que
  d'écarter tous les tags.
- **Tag acheté mais pas encore dans la liste** : il est écarté comme tout tag hors liste ; il ne peut être ni
  enregistré ni scanné tant qu'une nouvelle version de la liste n'est pas déployée (FR-001).
- **Scans répétés d'un même tag hors liste** : chacun est ignoré de la même façon ; aucun ne crée de lecture.
- **Mode enregistrement** : un lecteur en mode enregistrement ne crée pas de lecture de production ; une lecture hors
  liste n'entre pas dans la session (FR-003).
- **Tag hors liste déjà associé à un seau avant la révision** : son association, ses lectures et le tag sont
  supprimés au déploiement (FR-007) ; le seau reste, sans ce tag.
- **Tag retiré plus tard de la liste** : une nouvelle version de la liste qui ne contient plus un tag déjà en base ne
  supprime ni ce tag ni ses lectures ; seuls ses nouveaux scans et enregistrements sont écartés (FR-007).
- **Comptages passés** : les lectures supprimées disparaissent des consultations et du tableau de bord (spec `007`),
  y compris pour les jours passés ; c'est voulu, ces lectures ne concernaient pas des tags achetés.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Le système DOIT disposer d'une liste de référence des tags autorisés, initialisée avec les 5 008 UID du
  fichier fourni (`src/main/resources/tags/rfid_tag_list.csv`, d'abord remis dans `doc/`). La liste est livrée avec l'application et n'est pas modifiable depuis celle-ci : l'ajout ou le retrait de tags se
  fait en modifiant le fichier puis en déployant une nouvelle version (Clarifications 2026-09-26).
- **FR-002**: Le système DOIT déterminer si un UID est dans la liste de référence en comparant ses **12 derniers
  caractères**, après suppression des espaces en début et fin et passage en majuscules, aux 12 derniers caractères des
  lignes de la liste (Clarifications 2026-09-26). Un UID de moins de 12 caractères n'est jamais dans la liste. Cette
  normalisation ne sert qu'à la comparaison : l'UID enregistré n'est pas modifié. Le chargement de la liste DOIT
  refuser une liste où deux lignes ont les mêmes 12 derniers caractères, puisqu'elles ne se distingueraient plus.
- **FR-003**: Pendant une session d'enregistrement, une lecture de tag hors liste (scan unitaire ou envoi par lot,
  spec `011`) NE DOIT PAS être conservée dans la session ni affichée sur la page d'enregistrement.
- **FR-004**: Le service d'association de tags à un seau DOIT refuser en entier (`400`, rien n'est enregistré) une
  requête contenant au moins un UID hors liste, et nommer ces UID dans sa réponse. Aucune confirmation ne permet de
  passer outre (Clarifications 2026-09-26, révision). La confirmation d'un tag déjà associé à un autre seau (spec
  `003`) reste inchangée.
- **FR-005**: Les scans en mode enregistrement DOIVENT continuer à recevoir une réponse de même forme qu'aujourd'hui
  (statut `200`, mêmes champs, `isCompliant` à vrai), que le tag soit dans la liste ou non ; seul le message indique
  qu'une lecture hors liste est ignorée ("Registration read ignored: tag not in reference list"), comme il le fait
  déjà pour une lecture sans session ouverte.
- **FR-006**: Lors d'un scan en mode production d'un tag hors liste, le système NE DOIT créer ni tag ni lecture
  (Clarifications 2026-09-26, révision). Le lecteur DOIT recevoir une réponse `200` au format habituel, avec un message
  indiquant que le tag est ignoré car hors liste, pour que son logiciel n'ait rien à changer.
- **FR-007**: Au déploiement de la révision, le système DOIT supprimer une fois les tags hors liste déjà en base,
  avec leurs lectures, l'historique de conformité de ces lectures, leurs lectures de session d'enregistrement et leur
  association à un seau (Clarifications 2026-09-26, révision). Les seaux, cueilleurs et lecteurs ne sont pas
  supprimés. Cette suppression NE DOIT avoir lieu qu'une fois : les démarrages suivants, y compris avec une liste de
  référence modifiée, ne suppriment rien.
- **FR-008**: Aucune indication "hors liste" NE DOIT être affichée, ni sur la page d'enregistrement ni sur l'écran de
  contrôle de la ligne ; la consultation d'une liste des tags hors liste est retirée.
- **FR-009**: *Retiré (révision 2026-09-26)* : la liste des tags hors liste de l'Administrateur n'existe plus.
- **FR-010**: Le contrôle NE DOIT PAS allonger sensiblement le traitement d'un scan : l'objectif de temps de réponse
  de la spec `004` (SC-004) reste tenu.

### Key Entities

- **Liste de référence des tags** : l'ensemble des UID de tags autorisés (5 008 à l'initialisation). Un tag y est
  identifié par les 12 derniers caractères de son UID, uniques dans la liste. Indépendante des tags connus de l'application : un UID peut être dans la liste sans avoir jamais été lu.
- **Tag** (existant, spec `003`) : tout tag en base est dans la liste de référence (après FR-007) ; aucune propriété
  "hors liste" n'est exposée.
- **Lecture (Record)** (existante, spec `004`) : ne porte plus d'indication "hors liste" ; une lecture ne concerne
  qu'un tag de la liste.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 0 tag hors liste lu pendant une session d'enregistrement n'apparaît sur la page ni n'est enregistré
  sur un seau, et 100 % des tags de la liste lus apparaissent et peuvent être enregistrés.
- **SC-002**: Avec les 5 008 UID du fichier fourni, chacun est reconnu comme dans la liste, quelle que soit sa casse,
  sous sa forme du fichier (`E28069152000…`) comme sous celle des lecteurs de production (`E28069150000…`, par exemple
  `E2806915000040287477C993`), et un UID dont les 12 derniers caractères n'y figurent pas est reconnu hors liste.
- **SC-003**: 100 % des scans de production d'un tag hors liste reçoivent une réponse `200` sans créer de tag ni de
  lecture, et 100 % des scans d'un tag de la liste, avec les UID tels que les lecteurs de production les envoient
  réellement, créent leur lecture comme aujourd'hui.
- **SC-004**: L'objectif de temps de réponse des scans (95 % en moins de 200 ms, spec `004`) reste tenu avec le
  contrôle actif.
- **SC-005**: Après le déploiement de la révision, la base ne contient aucun tag hors liste, et le nombre de tags et
  de lectures de tags de la liste est identique à celui d'avant le déploiement.
- **SC-006**: Aucun tag hors liste n'est associé à un seau, quel que soit le chemin (page ou appel direct au service d'association).

## Assumptions

- Le fichier `rfid_tag_list.csv` est la liste complète et à jour des tags achetés ; son format (un UID par ligne,
  sans en-tête) reste le même pour d'éventuelles mises à jour.
- Les utilisateurs n'ont pas besoin de voir les tags hors liste : aucune alerte, dans l'application ou par un canal
  externe, n'est prévue (révision 2026-09-26).
- Les droits d'accès suivent la spec `008` : l'enregistrement reste réservé aux Administrateurs.
- La sauvegarde faite avant chaque déploiement (spec `009`) suffit pour retrouver les données supprimées par FR-007 ;
  aucun export dédié n'est prévu.
