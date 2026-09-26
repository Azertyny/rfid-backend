# Feature Specification: Contrôle des tags par rapport à la liste de référence

**Feature Branch**: `010-controle-tags-reference`

**Created**: 2026-09-26

**Status**: Delivered (2026-09-26); comparison rule revised the same day after production feedback (FR-002: last 12 characters of the UID) and implemented (tasks T036–T040) — see Clarifications

**Input**: User description: "I would like to check and alert if a tag is not part of the list @doc/rfid_tag_list.csv in the app"

## Clarifications

### Session 2026-09-26

- Q: Comment la liste de référence est-elle mise à jour quand de nouveaux tags sont achetés ? → A: Liste figée livrée avec l'application ; on modifie le fichier et on déploie une nouvelle version.
- Q: Que se passe-t-il à l'enregistrement d'un seau dont des tags sont hors liste ? → A: Avertir, puis enregistrer après confirmation de l'Administrateur, comme pour un tag déjà associé à un autre seau.
- Q: Que devient un scan de production d'un tag hors liste ? → A: La lecture est créée comme aujourd'hui et marquée "tag hors liste" ; ni sa conformité ni les comptages ne changent.
- Q: Quand la confirmation montre un tag hors liste que l'Administrateur veut écarter, comment fait-il ? → A: Il annule toute la session et recommence sans ce tag ; aucun retrait d'une lecture isolée n'est ajouté.
- Q: Un même tag lu une fois en minuscules et une fois en majuscules doit-il être traité comme un seul tag ? → A: La casse n'est ignorée que pour la comparaison à la liste ; les UID restent enregistrés tels que reçus, unifier leur casse est hors périmètre.
- Q: Sur quel écran le tag scanné aurait-il dû apparaître comme hors liste ? → A: L'écran de contrôle de la ligne (`reader.html`). Constaté en production le 2026-09-26, connecté en Opérateur : toutes les lectures de la ligne y sont affichées "hors liste", y compris celles de tags achetés, si bien qu'un tag étranger ne se distingue pas.
- Q: À quoi ressemble l'UID d'un tag acheté tel que l'application le reçoit en production ? → A: `E2806915000040287477C993`, alors que la liste contient `E2806915200040287477C993` (ligne 1469) : seul le 9e caractère diffère (`0` au lieu de `2`). Les 5 008 lignes de la liste ont `2000` en positions 9 à 12 ; les lecteurs de production envoient `0000` à cet endroit.
- Q: Comment comparer les UID reçus des lecteurs à la liste, puisqu'ils diffèrent de ses lignes ? → A: Seuls les 12 derniers caractères de l'UID comptent. Toutes les lignes de la liste commencent par les mêmes 12 caractères (`E28069152000`) et leurs 12 derniers caractères sont tous différents : ils suffisent à identifier chaque tag acheté.

## Contexte

Le fichier `rfid_tag_list.csv` (fourni dans `doc/`, livré avec l'application sous `src/main/resources/tags/`) recense les 5 008 tags RFID achetés pour l'exploitation : un identifiant (UID) par
ligne, 24 caractères hexadécimaux majuscules, sans en-tête ni doublon. Aujourd'hui, l'application accepte n'importe
quel UID non vide : un tag inconnu scanné par un lecteur est créé à la volée (spec `004`, FR-002), et un Administrateur
peut enregistrer n'importe quel tag lu sur un seau (spec `003`). Un tag étranger (tag d'un autre site, badge, lecture
parasite, UID mal lu) passe donc inaperçu et peut fausser le comptage d'un cueilleur.

Cette fonctionnalité fait de cette liste la **liste de référence** des tags attendus, et signale tout tag qui n'y
figure pas (**tag hors liste**).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Alerte lors de l'enregistrement de tags sur un seau (Priority: P1)

Un Administrateur enregistre les tags d'un seau depuis la page d'enregistrement des tags. Parmi les tags lus par le
lecteur du poste, ceux qui ne figurent pas dans la liste de référence sont clairement signalés avant l'enregistrement,
pour qu'il retire le tag suspect ou décide en connaissance de cause.

**Why this priority**: c'est à l'enregistrement qu'un tag entre dans le suivi d'un seau ; l'arrêter là évite qu'un
tag étranger ne produise ensuite des lectures attribuées à un cueilleur. C'est aussi le parcours le plus simple à
livrer seul.

**Independent Test**: démarrer une session d'enregistrement, faire lire un tag de la liste et un tag hors liste, et
vérifier que seul le second est signalé, puis vérifier le comportement à l'enregistrement du seau.

**Acceptance Scenarios**:

1. **Given** une session d'enregistrement en cours, **When** le lecteur du poste lit un tag présent dans la liste de
   référence, **Then** le tag s'affiche normalement, sans alerte.
2. **Given** une session d'enregistrement en cours, **When** le lecteur du poste lit un tag absent de la liste de
   référence, **Then** le tag s'affiche avec une alerte visible "tag hors liste", distincte de l'alerte existante
   "tag déjà associé à un autre seau".
3. **Given** au moins un tag hors liste parmi les tags lus, **When** l'Administrateur enregistre le seau sans avoir
   confirmé, **Then** l'enregistrement n'a pas lieu et le système lui demande de confirmer en nommant les tags hors
   liste (FR-004).
4. **Given** cette demande de confirmation, **When** l'Administrateur confirme, **Then** tous les tags lus, hors liste
   compris, sont enregistrés sur le seau ; **When** il annule la confirmation, **Then** rien n'est enregistré et la
   session reste ouverte ; pour écarter le tag suspect, il annule la session et recommence sans ce tag (une session ne
   permet pas de retirer une lecture, spec `003`).
5. **Given** des tags enregistrés sur un seau directement via le service d'association (sans passer par la page),
   **When** la liste fournie contient un tag hors liste sans confirmation explicite, **Then** rien n'est enregistré et
   la réponse nomme les tags hors liste ; avec confirmation explicite, l'enregistrement a lieu.

---

### User Story 2 - Alerte lors d'un scan en production (Priority: P2)

Un lecteur de ligne scanne un seau au passage. Si le tag lu n'est pas dans la liste de référence, la lecture est
signalée comme suspecte, pour qu'un Opérateur ou un Administrateur puisse la repérer et la vérifier.

**Why this priority**: les tags hors liste encore présents en production (tags enregistrés avant cette
fonctionnalité, lectures parasites) doivent être visibles, mais la ligne ne doit pas être ralentie ni interrompue.

**Independent Test**: faire scanner par un lecteur de production un tag hors liste, puis vérifier que la lecture est
signalée sur l'écran de contrôle de la ligne et dans la consultation des lectures.

**Acceptance Scenarios**:

1. **Given** un lecteur en mode production, **When** il scanne un tag présent dans la liste de référence, **Then**
   le comportement actuel est inchangé (spec `004`).
2. **Given** un lecteur en mode production, **When** il scanne un tag hors liste, **Then** la lecture est créée
   comme aujourd'hui, avec la conformité déclarée par le lecteur, et porte une indication "tag hors liste" visible sur l'écran de contrôle de la ligne et dans la
   consultation des lectures.
3. **Given** des lectures de tags hors liste, **When** un Administrateur consulte les tags hors liste (User Story 3),
   **Then** il voit, pour chacun, le nombre de ses lectures et la date de la dernière (révisé lors de
   `/speckit-plan`, voir FR-008).

---

### User Story 3 - Repérer les tags déjà enregistrés hors liste (Priority: P3)

Un Administrateur veut savoir quels tags déjà connus de l'application (enregistrés sur des seaux ou créés par des
scans avant cette fonctionnalité) ne figurent pas dans la liste de référence, pour faire le ménage.

**Why this priority**: utile une fois, au déploiement, puis ponctuellement ; les deux premiers parcours couvrent
les nouveaux cas.

**Independent Test**: avec des tags existants dont certains hors liste, afficher la liste des tags hors liste et
vérifier qu'elle contient exactement ceux-là, avec leur seau le cas échéant.

**Acceptance Scenarios**:

1. **Given** des tags connus de l'application dont certains sont hors liste, **When** un Administrateur consulte les
   tags hors liste, **Then** il voit chacun de ces tags, avec le numéro de son seau s'il en a un, et aucun tag de la
   liste de référence.
2. **Given** aucun tag hors liste, **When** un Administrateur consulte les tags hors liste, **Then** un message
   indique qu'il n'y en a aucun.

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
  dont la liste manque, est vide ou contient une ligne qui n'est pas un UID ne doit pas démarrer, plutôt que de
  signaler tous les tags comme hors liste.
- **Tag acheté mais pas encore dans la liste** : il est signalé comme hors liste ; l'Administrateur peut quand même
  l'enregistrer après confirmation (FR-004), et le signalement disparaît une fois la liste mise à jour.
- **Lectures répétées d'un même tag hors liste** : le dédoublonnage existant (spec `004`, FR-008) s'applique ; un
  tag hors liste ne produit pas plus d'alertes que de lectures retenues.
- **Mode enregistrement** : un lecteur en mode enregistrement ne crée pas de lecture de production ; l'alerte
  s'affiche uniquement sur la page d'enregistrement (User Story 1).
- **Tag hors liste déjà associé à un seau avant la fonctionnalité** : il n'est ni retiré ni modifié
  automatiquement ; il apparaît dans la liste de la User Story 3 et ses lectures sont signalées (User Story 2).

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
- **FR-003**: La page d'enregistrement des tags DOIT signaler chaque tag lu hors liste, dès son affichage, par une
  alerte distincte de celle d'un tag déjà associé à un autre seau.
- **FR-004**: Lors de l'enregistrement de tags sur un seau (page d'enregistrement comme service d'association),
  lorsqu'au moins un tag est hors liste, le système NE DOIT RIEN enregistrer sans confirmation explicite de l'Administrateur, comme pour un tag déjà
  associé à un autre seau (spec `003`), et DOIT nommer les tags hors liste dans sa réponse. Après confirmation, tous
  les tags demandés sont enregistrés, hors liste compris (Clarifications 2026-09-26). Les deux confirmations (tag
  hors liste, tag déjà associé à un autre seau) sont distinctes : confirmer l'une ne vaut pas confirmation de
  l'autre.
- **FR-005**: Les scans en mode enregistrement DOIVENT continuer à recevoir la même réponse qu'aujourd'hui ; le
  contrôle de la liste n'a lieu que sur la page d'enregistrement.
- **FR-006**: Lors d'un scan en mode production d'un tag hors liste, le système DOIT créer la lecture comme
  aujourd'hui (même conformité, même dédoublonnage, même rattachement au cueilleur) et la marquer "tag hors liste"
  (Clarifications 2026-09-26). Le marquage ne change ni la conformité ni les comptages. Le lecteur DOIT recevoir une réponse au format habituel, sans erreur, pour que son logiciel
  n'ait rien à changer.
- **FR-007**: L'écran de contrôle de la ligne DOIT signaler visiblement une lecture de tag hors liste.
- **FR-008**: La consultation des lectures DOIT indiquer, pour chaque lecture, si son tag est hors liste.
  **Révisé lors de `/speckit-plan`** : la seule consultation des lectures est celle des 10 dernières lectures d'un
  lecteur (spec `005`, écran de contrôle de la ligne) ; un filtre sur 10 lignes n'apporte rien. Retrouver les lectures
  de tags hors liste passe par la liste de FR-009, qui donne pour chaque tag le nombre de ses lectures et la date de la
  dernière (research R6).
- **FR-009**: Un Administrateur DOIT pouvoir consulter la liste des tags connus de l'application qui sont hors
  liste, avec le numéro de seau associé le cas échéant, le nombre de lectures du tag et la date de la dernière.
- **FR-010**: Le contrôle NE DOIT PAS allonger sensiblement le traitement d'un scan : l'objectif de temps de réponse
  de la spec `004` (SC-004) reste tenu.

### Key Entities

- **Liste de référence des tags** : l'ensemble des UID de tags autorisés (5 008 à l'initialisation). Un tag y est
  identifié par les 12 derniers caractères de son UID, uniques dans la liste. Indépendante des tags connus de l'application : un UID peut être dans la liste sans avoir jamais été lu.
- **Tag** (existant, spec `003`) : gagne une propriété dérivée "hors liste", vraie si son UID n'est pas dans la liste
  de référence.
- **Lecture (Record)** (existante, spec `004`) : doit permettre de savoir si le tag lu était hors liste.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % des tags hors liste lus pendant une session d'enregistrement sont signalés sur la page avant
  l'enregistrement du seau, et aucun tag de la liste n'est signalé à tort.
- **SC-002**: Avec les 5 008 UID du fichier fourni, chacun est reconnu comme dans la liste, quelle que soit sa casse,
  sous sa forme du fichier (`E28069152000…`) comme sous celle des lecteurs de production (`E28069150000…`, par exemple
  `E2806915000040287477C993`), et un UID dont les 12 derniers caractères n'y figurent pas est reconnu hors liste.
- **SC-003**: Une lecture de tag hors liste en production est visible sur l'écran de contrôle de la ligne dans le
  même délai qu'une lecture ordinaire, et aucune lecture d'un tag de la liste n'y est signalée à tort, avec les UID
  tels que les lecteurs de production les envoient réellement.
- **SC-004**: L'objectif de temps de réponse des scans (95 % en moins de 200 ms, spec `004`) reste tenu avec le
  contrôle actif.
- **SC-005**: Un Administrateur obtient la liste complète des tags déjà enregistrés hors liste en une seule
  consultation.
- **SC-006**: Aucun tag hors liste n'est associé à un seau sans confirmation explicite d'un Administrateur.

## Assumptions

- Le fichier `rfid_tag_list.csv` est la liste complète et à jour des tags achetés ; son format (un UID par ligne,
  sans en-tête) reste le même pour d'éventuelles mises à jour.
- "Alerter" signifie un signalement visible dans l'application (page d'enregistrement, écran de contrôle de la ligne,
  consultation des lectures) ; aucune notification par e-mail, SMS ou autre canal externe n'est prévue.
- Les droits d'accès suivent la spec `008` : l'enregistrement et la liste des tags hors liste sont réservés aux
  Administrateurs ; la consultation des lectures reste ouverte aux Opérateurs et Administrateurs.
- Les tags et lectures existants ne sont ni supprimés ni modifiés par cette fonctionnalité ; l'indication "hors
  liste" d'une lecture reflète la liste de référence en vigueur.
