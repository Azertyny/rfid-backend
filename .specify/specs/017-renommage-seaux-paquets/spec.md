# Feature Specification: Renommer les « seaux » en « paquets »

**Feature Branch**: `017-renommage-seaux-paquets`

**Created**: 2026-11-15

**Status**: Delivered (2026-11-15) — see [plan.md](plan.md) and [tasks.md](tasks.md)

**Input**: User description: "J'aimerais renommer les "sceaux" par une notion plus abstraite comme "paquets""

## Contexte

Le contenant auquel on fixe les tags RFID et qu'on affecte à un cueilleur s'appelle aujourd'hui un **seau** dans
toute l'interface : l'entrée de menu « Seaux » de chaque page, la page d'affectation, l'enregistrement des tags
(« Numéro de seau »), les messages de confirmation et d'erreur, l'explication de l'écart Cheat dans les paramètres,
le lien d'anomalie du tableau de bord et la fiche cueilleur. Les sites n'utilisent pas tous des seaux (cagettes,
plateaux, lots…) : le mot ne correspond pas à ce que les équipes manipulent et les trompe.

Le terme retenu est **paquet** (pluriel **paquets**), plus abstrait. Il est masculin comme « seau », ce qui garde
les accords (« le paquet n°12 », « ce paquet », « un paquet ») ; seul le mot change.

Le changement est un changement **de vocabulaire** : aucune règle de gestion ne change (affectation, désaffectation,
suppression, enregistrement des tags, règle Cheat, comptage des caisses). Les noms techniques déjà en anglais
(`bucket` dans les adresses, les données et le code) restent tels quels.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Lire « paquet » partout dans l'interface (Priority: P1)

Un Administrateur ou un Opérateur navigue dans l'application : le menu, les titres, les colonnes, les boutons, les
fenêtres de confirmation et les messages parlent de **paquets**, jamais de seaux.

**Why this priority**: c'est tout l'objet de la demande ; un seul « seau » restant suffit à entretenir la confusion.

**Independent Test**: parcourir chaque page de l'application (connecté en Administrateur puis en Opérateur, et le
kiosque d'une ligne) et chercher le mot « seau » dans tout le texte affiché, messages compris.

**Acceptance Scenarios**:

1. **Given** un utilisateur connecté sur n'importe quelle page, **When** il regarde le menu, **Then** l'entrée qui
   mène à l'affectation s'appelle « Paquets ».
2. **Given** la page d'affectation, **When** elle s'affiche, **Then** l'onglet du navigateur, le titre, le
   sous-titre (« Affectation des paquets aux cueilleurs ») et la colonne (« N° du paquet ») parlent de paquets.
3. **Given** la page d'affectation, **When** l'utilisateur affecte, désaffecte ou supprime un paquet, **Then** le
   titre et le texte de chaque fenêtre (« Affecter le paquet n°12 », « Désaffecter le paquet », « Supprimer le
   paquet n°12 ? ») et chaque message d'erreur (« Ce paquet ou ce cueilleur n'existe plus. ») parlent de paquets.
4. **Given** aucune donnée, **When** la page d'affectation s'affiche, **Then** le message vide indique « Aucun
   paquet. Les paquets sont créés à l'enregistrement des tags. »

---

### User Story 2 - Enregistrer des tags dans un paquet (Priority: P1)

L'Administrateur qui enregistre des tags lit « Enregistrement des tags sur un paquet » et saisit un « Numéro de
paquet ». Les badges (« Déjà sur ce paquet », « Paquet 12 »), la fenêtre de déplacement (« Tags déjà associés à un
autre paquet ») et les messages de succès ou de refus emploient le même mot.

**Why this priority**: c'est l'écran où le mot est le plus présent et où un terme faux gêne le plus le geste.

**Independent Test**: enregistrer des tags sur un nouveau numéro, puis sur un numéro qui a déjà des tags d'un autre
paquet, puis tenter un numéro invalide ; relire chaque libellé et message.

**Acceptance Scenarios**:

1. **Given** la page d'enregistrement des tags, **When** elle s'affiche, **Then** le titre, l'aide et le champ
   parlent de paquet.
2. **Given** des tags lus dont certains sont déjà sur un autre paquet, **When** l'Administrateur valide, **Then** la
   fenêtre annonce qu'ils « seront retirés de leur paquet actuel et associés au paquet N ».
3. **Given** un enregistrement réussi, **When** le message s'affiche, **Then** il indique « le paquet N contient
   maintenant X tag(s) ».
4. **Given** un numéro vide ou invalide, **When** l'Administrateur valide, **Then** le message demande un « numéro
   de paquet ».

---

### User Story 3 - Textes explicatifs et icône cohérents (Priority: P2)

Les autres textes qui citent le contenant emploient « paquet » : l'explication de l'écart Cheat dans les paramètres
(« Deux tags d'un même paquet… »), l'info-bulle du lien d'anomalie Cheat du tableau de bord, le refus de supprimer
un cueilleur (« Ce cueilleur a encore un paquet affecté… »). L'icône de l'entrée de menu, aujourd'hui un seau qui se
remplit, est remplacée par une icône neutre de colis ou de boîte.

**Why this priority**: moins fréquents que les écrans précédents, mais un reste de l'ancien terme ou une icône de
seau contredit le changement.

**Independent Test**: ouvrir les paramètres, survoler le lien Cheat du tableau de bord, tenter de supprimer un
cueilleur qui a un paquet ; regarder l'icône du menu sur chaque page.

**Acceptance Scenarios**:

1. **Given** la page des paramètres, **When** elle s'affiche, **Then** l'explication de l'écart Cheat parle de
   « deux tags d'un même paquet ».
2. **Given** le tableau de bord, **When** l'utilisateur survole le lien des lectures Cheat, **Then** l'info-bulle
   parle de « deux tags d'un même paquet ».
3. **Given** un cueilleur qui a un paquet affecté, **When** l'Administrateur tente de le supprimer, **Then** le
   refus parle de « paquet affecté ».
4. **Given** n'importe quelle page, **When** le menu s'affiche, **Then** l'entrée « Paquets » porte la même icône
   de colis sur toutes les pages.

---

### User Story 4 - Documentation à jour (Priority: P3)

La documentation vivante du projet (description des cas d'utilisation, glossaire du domaine, descriptions de l'API)
appelle le contenant « paquet », pour qu'un nouveau lecteur ne retrouve pas l'ancien terme.

**Why this priority**: sans effet pour les utilisateurs de terrain, mais évite que l'ancien vocabulaire revienne
dans les prochaines évolutions.

**Independent Test**: chercher « seau » dans la documentation vivante ; seules les spécifications déjà livrées
(historiques) peuvent encore le contenir.

**Acceptance Scenarios**:

1. **Given** la documentation des cas d'utilisation et le glossaire du projet, **When** on y cherche « seau »,
   **Then** on ne le trouve plus, sauf pour mentionner l'ancien nom.
2. **Given** les descriptions de l'API (champs et réponses liés au contenant), **When** on les lit, **Then** elles
   parlent de paquet.

---

### Edge Cases

- **Pluriel et majuscules** : « Seaux » → « Paquets », « Seau » → « Paquet », « seau » → « paquet », y compris dans
  les textes construits avec un numéro (« Paquet 12 », « paquet n°12 »).
- **Élisions et contractions** : « du seau » → « du paquet », « au seau » → « au paquet », « de seau » → « de
  paquet » ; aucun accord à reprendre puisque les deux mots sont masculins.
- **Mot « caisse »** : une caisse reste l'unité comptée par le scan (tableau de bord, statistiques) ; elle n'est pas
  renommée et ne doit pas être confondue avec un paquet.
- **Spécifications historiques** : les spécifications déjà livrées (`001` à `015`) gardent leur texte d'origine ; on
  ne réécrit pas l'historique.
- **Adresses et données** : un favori ou un lien vers la page d'affectation continue de fonctionner ; aucune donnée
  existante (numéros, affectations, tags, lectures) n'est modifiée ni perdue.
- **Lecteurs RFID et kiosque** : les échanges avec les lecteurs ne changent pas ; aucun lecteur ni kiosque n'a à
  être reconfiguré.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Tout texte affiché par l'application (menu, titres d'onglet et de page, sous-titres, en-têtes de
  colonne, libellés de champ, boutons, fenêtres de confirmation, badges, info-bulles, messages de succès, d'erreur
  et d'état vide) MUST désigner le contenant par « paquet » / « paquets » et ne MUST plus contenir « seau » /
  « seaux ».
- **FR-002**: Le remplacement MUST conserver la casse et le nombre de l'original (« Seaux » → « Paquets »,
  « seau » → « paquet ») et la grammaire de la phrase.
- **FR-003**: L'entrée de menu menant à l'affectation MUST s'appeler « Paquets » et porter, sur toutes les pages,
  une même icône de colis ou de boîte à la place de l'icône de seau.
- **FR-004**: Les messages que l'application renvoie à l'écran à propos du contenant (refus, erreurs) MUST employer
  « paquet », qu'ils soient produits par la page ou par le serveur.
- **FR-005**: Le changement MUST être purement de vocabulaire : affectation, désaffectation, suppression,
  enregistrement des tags, déplacement des tags entre contenants, règle Cheat et comptage des caisses MUST se
  comporter exactement comme avant.
- **FR-006**: Les données existantes (numéros, affectations, tags, lectures, historique) MUST rester intactes et
  visibles sous le nouveau nom, sans action de reprise.
- **FR-007**: Les adresses des pages et de l'API, ainsi que les échanges avec les lecteurs RFID et le kiosque, MUST
  rester inchangés, pour que favoris, lecteurs et intégrations continuent de fonctionner.
- **FR-008**: La documentation vivante (cas d'utilisation, glossaire du domaine, descriptions de l'API) MUST
  employer « paquet » ; les spécifications déjà livrées restent inchangées.
- **FR-009**: Le mot « caisse » (unité comptée par le scan) MUST rester inchangé.

### Key Entities

- **Paquet** (anciennement *seau*) : contenant numéroté auquel on fixe un ou plusieurs tags RFID et qu'on affecte à
  un cueilleur. Mêmes attributs et mêmes relations qu'avant (numéro unique, tags, cueilleur affecté, lectures) ;
  seul son nom affiché change.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 0 occurrence de « seau » ou « seaux » dans le texte affiché de toutes les pages de l'application,
  messages et fenêtres compris (aujourd'hui 45 réparties sur 10 pages).
- **SC-002**: 100 % des parcours existants autour du contenant (enregistrer des tags, affecter, réaffecter,
  désaffecter, supprimer) se déroulent comme avant, avec les mêmes résultats.
- **SC-003**: 0 donnée perdue ou modifiée : après mise en production, le nombre de paquets, d'affectations, de tags
  et de lectures est identique à celui de la veille.
- **SC-004**: Aucun lecteur RFID, kiosque ni favori n'a à être reconfiguré après la mise en production.
- **SC-005**: Un nouvel utilisateur formé avec le terme « paquet » retrouve chaque écran concerné sans rencontrer
  l'ancien terme.

## Assumptions

- « Paquet » est le terme retenu (proposé par le demandeur) ; s'il fallait un autre mot (« lot », « contenant »…),
  seul le libellé changerait, la portée resterait la même.
- Le renommage est limité au texte visible et à la documentation vivante ; les noms techniques en anglais
  (`bucket`) dans les adresses, les données et le code ne changent pas, ce qui évite toute migration et tout impact
  sur les lecteurs et les intégrations.
- L'interface n'existe qu'en français ; il n'y a pas de traduction à mettre à jour.
- Les spécifications livrées sont l'historique du projet et gardent « seau ».
- Les diagrammes de documentation qui nomment le contenant sont mis à jour avec la documentation vivante.
