# Feature Specification: Statuts de lecture — relecture ignorée, doublon / perdu et cheat

**Feature Branch**: `015-statuts-doublon-cheat`

**Created**: 2026-11-15

**Status**: Delivered (2026-11-15) — see [plan.md](plan.md) and [tasks.md](tasks.md)

**Input**: User description: "1 tag scanné dans les 30 dernières minutes à ne pas prendre en compte (paramètre) ; 1 tag scanné plusieurs fois dans la même journée -> statut doublon / perdu ; 2 tags scannés en même temps -> statut cheat (paramètre)"

## Contexte

Aujourd'hui (spec `004`), une lecture est soit conforme, soit non conforme, et trois règles traitent les lectures
répétées :

| Situation                                                        | Aujourd'hui (spec `004`)                                   | Avec cette fonctionnalité                          |
|------------------------------------------------------------------|------------------------------------------------------------|----------------------------------------------------|
| Même tag relu par le même lecteur moins de 10 s après (FR-008)    | Ignoré (même passage)                                      | Inchangé                                           |
| Même tag relu moins de 30 min après sa dernière lecture          | Nouvelle lecture **non conforme**, sans cueilleur (FR-009) | **Ignoré** : aucune lecture créée                  |
| Même tag relu plus tard dans la même journée                     | Nouvelle lecture **non conforme**, sans cueilleur (FR-009) | Nouvelle lecture au statut **Doublon / perdu**     |
| Même tag relu un jour suivant, sans nouvel enregistrement dans un seau | Lecture ordinaire sans cueilleur, verdict du lecteur (FR-009) | Nouvelle lecture au statut **Doublon / perdu** |
| Deux tags d'un même seau lus en même temps (2 s) par le même lecteur (FR-010) | Une seule lecture, sans signalement           | Une seule lecture, au statut **Cheat**             |

Deux défauts sont visés : une caisse qui repasse devant un lecteur peu après son comptage (retour sur la ligne,
passage devant une autre ligne) crée aujourd'hui de fausses lectures non conformes ; et rien ne distingue une
caisse réellement non conforme d'un tag retrouvé ou d'une tentative de compter deux fois. Les deux durées (30 min,
« en même temps ») sont des **paramètres**.

Cette fonctionnalité revient sur la décision du 2026-09-24 (spec `004`, FR-006) de ne garder qu'une conformité
binaire : une lecture a désormais un **statut** parmi Conforme, Non conforme, Doublon / perdu et Cheat.

## Clarifications

### Session 2026-11-15

- Q: Quels tags déclenchent le statut Cheat quand le même lecteur les lit à moins de 2 s d'écart ? → A: Deux tags du
  même seau, donc du même cueilleur (le cas « deux tags sur une caisse » de la spec `004`, FR-010). Deux tags de
  seaux différents ne sont pas un Cheat.
- Q: Que doit enregistrer le système quand un Cheat est détecté ? → A: Une seule lecture pour la caisse, au statut
  Cheat, attribuée au cueilleur ; le second tag ne crée aucune lecture.
- Q: La règle des 30 minutes s'applique-t-elle quel que soit le lecteur ? → A: Oui, tous les lecteurs : un tag compté
  est ignoré pendant 30 min sur toutes les lignes.
- Q: Qui doit pouvoir modifier les deux durées « paramètre » (30 min, 2 s), et comment ? → A: L'Administrateur,
  depuis un écran de réglages de l'application, avec effet immédiat et sans redémarrage.
- Q: Une caisse au statut Cheat compte-t-elle dans le total de caisses du cueilleur sur le tableau de bord ? → A: Oui :
  elle reste dans son total et dans sa colonne d'activité ; le Cheat est signalé dans une colonne à part.
- Q: À partir de quand courent les 30 minutes pendant lesquelles un tag déjà compté est ignoré ? → A: Depuis la
  dernière lecture du tag, lectures ignorées comprises (délai glissant) : il faut 30 min sans aucune lecture pour
  qu'un rescan devienne Doublon / perdu.
- Q: Une lecture marquée Doublon / perdu ou Cheat par le système peut-elle être corrigée à la main, et par qui ? → A:
  Par l'Administrateur seulement ; l'Opérateur et le kiosque de la ligne gardent la bascule Conforme / Non conforme
  mais ne peuvent pas lever un Doublon / perdu ni un Cheat.
- Q: « Doublon / perdu » est-il un seul statut ou deux statuts distincts ? → A: Un seul statut, nommé
  « Doublon / perdu » : le système ne distingue pas un tag perdu puis retrouvé d'un tag scanné deux fois.
- Q: L'Administrateur doit-il pouvoir retrouver et corriger une lecture Doublon / perdu ou Cheat ancienne (par exemple
  un Cheat de la veille repéré sur le tableau de bord) ? → A: Oui : un écran Administrateur liste les lectures
  Doublon / perdu et Cheat de la période choisie et permet de les repasser à Conforme ou Non conforme.
- Q: Une lecture Doublon / perdu compte-t-elle dans le nombre de caisses du tableau de bord ? → A: Non : elle n'entre
  ni dans le total de caisses, ni dans les colonnes d'activité, ni dans le taux de conformité ; elle n'apparaît que
  dans son propre compteur et dans la liste des anomalies.
- Q: Un tag compté un jour précédent et jamais remis dans un seau est scanné aujourd'hui : quel statut a cette
  lecture ? → A: Doublon / perdu : tant qu'un tag compté n'a pas été enregistré à nouveau dans un seau, tout nouveau
  scan hors délai est un Doublon / perdu, quel que soit le jour.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ne pas compter un tag relu peu après son comptage (Priority: P1)

Une caisse dont le tag vient d'être compté repasse devant un lecteur dans la demi-heure : elle reste à portée, elle
est reposée sur la ligne ou elle passe devant le lecteur d'une autre ligne. Le système n'en tient pas compte : aucune
lecture n'est ajoutée et la lecture qui a compté le tag reste telle quelle.

**Why this priority**: c'est la source la plus fréquente de fausses lectures ; chaque relecture crée aujourd'hui une
lecture non conforme qui fausse le taux de conformité et encombre l'écran de la ligne.

**Independent Test**: scanner un tag d'un seau affecté, puis le rescanner 5 minutes plus tard : une seule lecture
existe, conforme et attribuée au cueilleur.

**Acceptance Scenarios**:

1. **Given** un tag compté il y a 5 minutes, **When** un lecteur de production le lit à nouveau, **Then** aucune
   lecture n'est créée, la première lecture est inchangée et le lecteur reçoit sa réponse habituelle, sans erreur.
2. **Given** un tag compté il y a 5 minutes puis relu (et ignoré) il y a 2 minutes, **When** il est relu 29 minutes
   après cette dernière relecture, **Then** il est encore ignoré : le délai part de la dernière lecture du tag,
   lectures ignorées comprises.
3. **Given** un délai paramétré à 10 minutes au lieu de 30, **When** un tag compté il y a 15 minutes est relu,
   **Then** la relecture n'est plus ignorée (elle relève de la User Story 2).
4. **Given** un tag compté il y a 5 minutes puis enregistré à nouveau dans un seau, **When** il est scanné,
   **Then** il est compté normalement : un nouvel enregistrement remet le tag à neuf (spec `004`, FR-009).

---

### User Story 2 - Signaler un tag déjà compté qui est scanné à nouveau (Priority: P1)

Un tag déjà compté est scanné à nouveau, une fois le délai de la User Story 1 passé, le même jour ou un jour
suivant : il a été perdu puis retrouvé, ou réutilisé sans être enregistré dans un seau. Le système crée une lecture au statut **Doublon / perdu**,
que l'Opérateur et l'Administrateur distinguent d'une caisse non conforme.

**Why this priority**: sans ce statut, ces lectures se confondent avec les caisses réellement non conformes ; avec
la User Story 1, elle remplace entièrement la règle actuelle du rescan d'un tag déjà compté.

**Independent Test**: scanner un tag à 9 h puis à 11 h le même jour : deux lectures existent, la première inchangée,
la seconde au statut Doublon / perdu et sans cueilleur.

**Acceptance Scenarios**:

1. **Given** un tag compté à 9 h, **When** il est scanné à 11 h le même jour sans avoir été enregistré à nouveau
   dans un seau, **Then** une nouvelle lecture est créée au statut Doublon / perdu, sans cueilleur, quel que soit le
   verdict envoyé par le lecteur ; la lecture de 9 h est inchangée.
2. **Given** ce même tag, **When** il est scanné une troisième fois à 15 h, **Then** une nouvelle lecture
   Doublon / perdu est créée.
3. **Given** un tag compté hier et pas enregistré à nouveau, **When** il est scanné aujourd'hui, **Then** la lecture
   créée est aussi Doublon / perdu, sans cueilleur, quel que soit le verdict du lecteur.
4. **Given** un tag compté à 9 h puis enregistré à nouveau dans un seau à 10 h, **When** il est scanné à 11 h,
   **Then** la lecture est comptée normalement pour le cueilleur du seau.

---

### User Story 3 - Signaler deux tags lus en même temps (Priority: P1)

Deux tags d'un même seau sont lus par le même lecteur à un instant d'écart : une seule caisse est passée, avec deux
tags du même cueilleur. Le système garde une seule lecture pour cette caisse et lui donne le statut **Cheat**,
attribuée au cueilleur, au lieu d'absorber le second tag sans trace.

**Why this priority**: c'est le seul cas de fraude que le système peut détecter seul ; aujourd'hui il est neutralisé
(une seule caisse comptée) mais invisible.

**Independent Test**: faire lire par le même lecteur deux tags d'un même seau à 1 s d'écart : une seule lecture
existe, au statut Cheat, attribuée au cueilleur du seau.

**Acceptance Scenarios**:

1. **Given** deux tags d'un même seau affecté à un cueilleur, **When** le même lecteur de production les lit à moins
   de 2 s d'écart, **Then** une seule lecture existe pour la caisse, au statut Cheat et attribuée au cueilleur, quel
   que soit le verdict envoyé par le lecteur ; le second tag ne crée aucune lecture et les deux tags sortent du seau.
2. **Given** les deux mêmes tags, **When** ils sont lus à plus de 2 s d'écart ou par deux lecteurs différents,
   **Then** chacun est compté normalement (deux lectures).
3. **Given** deux tags de seaux différents, **When** le même lecteur les lit à moins de 2 s d'écart, **Then** chacun
   est compté normalement : ce n'est pas un Cheat.
4. **Given** un délai paramétré à 5 s au lieu de 2 s, **When** deux tags d'un même seau sont lus à 3 s d'écart,
   **Then** le statut Cheat s'applique.
5. **Given** une lecture au statut Cheat, **When** un des deux tags est relu dans les 30 minutes, **Then** il est
   ignoré (User Story 1) ; **When** il est relu plus tard, **Then** la lecture créée est Doublon / perdu (User
   Story 2).

---

### User Story 4 - Voir et corriger les statuts (Priority: P2)

L'Opérateur voit le statut de chaque lecture sur l'écran de la ligne ; l'Administrateur voit sur le tableau de bord
combien de lectures Doublon / perdu et Cheat la période contient, et quels cueilleurs ont des lectures Cheat. Une
lecture signalée à tort peut être corrigée à la main, par l'Administrateur seulement : sur l'écran de la ligne tant
qu'elle y figure, ou plus tard depuis la liste des lectures Doublon / perdu et Cheat de la période.

**Why this priority**: les statuts n'ont de valeur que s'ils sont visibles, mais les trois règles de scan
fonctionnent et se vérifient sans cet affichage.

**Independent Test**: après un doublon et un cheat sur une ligne, l'écran de la ligne affiche les deux statuts, et
le tableau de bord du jour compte 1 Doublon / perdu et 1 Cheat, ce dernier sur la ligne du cueilleur.

**Acceptance Scenarios**:

1. **Given** des lectures aux quatre statuts sur une ligne, **When** l'Opérateur consulte les dernières lectures de
   la ligne, **Then** chacune affiche son statut, les quatre étant distinguables d'un coup d'œil.
2. **Given** une période contenant 3 lectures Doublon / perdu et 2 lectures Cheat, **When** on affiche le tableau de
   bord, **Then** la synthèse donne ces deux nombres et le tableau par cueilleur indique le nombre de Cheat de chaque
   cueilleur.
3. **Given** une lecture Cheat ou Doublon / perdu signalée à tort, **When** l'Administrateur la passe à Conforme ou
   Non conforme, **Then** le nouveau statut fait foi et la modification est tracée (spec `005`).
4. **Given** des chiffres affichés, **When** on exporte le tableau par cueilleur, **Then** l'export contient la
   colonne Cheat.
5. **Given** un cueilleur avec 30 lectures sur la période dont 2 au statut Cheat, **When** on affiche le tableau par
   cueilleur, **Then** sa ligne indique 30 caisses au total et 2 dans la colonne Cheat.
6. **Given** une lecture Cheat ou Doublon / perdu, **When** un Opérateur ou le kiosque de la ligne tente d'en changer
   le statut, **Then** la demande est refusée et le statut est inchangé ; l'écran de la ligne ne propose pas la
   bascule pour ces lectures.
7. **Given** un Cheat d'hier et un Doublon / perdu d'aujourd'hui, **When** l'Administrateur ouvre la liste des
   anomalies sur la période hier–aujourd'hui, **Then** les deux lectures sont listées avec leur date, leur ligne,
   leur tag, leur statut et, pour le Cheat, son cueilleur ; les lectures Conforme et Non conforme n'y figurent pas.
8. **Given** cette liste, **When** l'Administrateur passe le Cheat d'hier à Conforme, **Then** la lecture quitte la
   liste, le tableau de bord de la période compte un Cheat de moins et la modification est tracée.
9. **Given** un Opérateur connecté ou le kiosque d'une ligne, **When** il tente d'ouvrir la liste des anomalies,
   **Then** la demande est refusée.
10. **Given** une période contenant 100 caisses dont 10 Non conforme et 2 Cheat, plus 5 lectures Doublon / perdu,
    **When** on affiche le tableau de bord, **Then** le total est 100 (pas 105), les non conformes 12, le taux de
    conformité 88 %, le compteur Doublon / perdu 5, et la ligne « Non attribué » ne contient aucun des 5 doublons.

---

### User Story 5 - Régler les deux durées (Priority: P2)

L'Administrateur ouvre un écran de réglages, y lit les deux durées en vigueur (relecture ignorée, écart « en même
temps ») et les modifie lui-même ; la nouvelle valeur s'applique aux scans suivants, sans intervention sur le serveur.

**Why this priority**: les valeurs par défaut (30 min, 2 s) suffisent pour démarrer ; le réglage sert à les ajuster
à la cadence réelle des lignes une fois la fonctionnalité en service.

**Independent Test**: passer le délai de relecture ignorée de 30 à 10 minutes depuis l'écran, puis rescanner un tag
compté 15 minutes plus tôt : la lecture créée est Doublon / perdu, sans redémarrage du serveur.

**Acceptance Scenarios**:

1. **Given** une installation neuve, **When** l'Administrateur ouvre l'écran de réglages, **Then** il lit 30 minutes
   et 2 secondes.
2. **Given** l'Administrateur a enregistré 10 minutes pour la relecture ignorée, **When** un tag compté 15 minutes
   plus tôt est relu, **Then** la relecture n'est plus ignorée ; les lectures déjà enregistrées ne changent pas.
3. **Given** l'Administrateur, **When** il saisit une durée négative, non numérique ou au-delà du maximum admis,
   **Then** la saisie est refusée avec un message explicite et la valeur en vigueur est conservée.
4. **Given** un Opérateur connecté ou le kiosque d'une ligne, **When** il tente de lire ou de modifier les réglages,
   **Then** la demande est refusée.
5. **Given** une durée modifiée, **When** l'Administrateur rouvre l'écran, **Then** il voit la valeur en vigueur, qui
   l'a modifiée en dernier et quand.

---

### Edge Cases

- **Tag resté à portée d'un lecteur** : relu en continu, il reste ignoré tant que deux lectures ne sont pas séparées
  de plus de 30 min (délai glissant) ; il ne produit donc pas un doublon toutes les 30 min.
- **Relecture à cheval sur minuit** : le changement de jour ne joue plus aucun rôle. Un tag compté à 23 h 50 et relu
  à 0 h 10 est ignoré (moins de 30 min) ; relu à 0 h 30, il donne une lecture Doublon / perdu.
- **Tag jamais compté et sans seau** (jamais enregistré, ou libéré par la suppression de son seau) : son premier scan
  reste une lecture ordinaire sans cueilleur, avec le verdict du lecteur (spec `004`) ; c'est ce scan qui le compte.
- **Même passage devant le même lecteur** (spec `004`, FR-008) : inchangé, y compris le verdict non conforme qui
  l'emporte pendant le passage. Une relecture ignorée au titre des 30 min, elle, ne modifie jamais la lecture
  existante, quel que soit son verdict.
- **Réglage modifié en cours de journée** : la nouvelle valeur s'applique dès le scan suivant, à partir des dernières
  lectures déjà connues ; un tag compté 15 min plus tôt devient donc un doublon dès que le délai passe sous 15 min.
- **Délai de 30 min réglé à zéro** : la règle est désactivée ; tout rescan d'un tag déjà compté, hors même passage,
  donne une lecture Doublon / perdu.
- **Écart « en même temps » réglé à zéro** : la détection Cheat est désactivée ; deux tags d'un même seau lus
  ensemble donnent alors deux lectures ordinaires, donc deux caisses pour le cueilleur.
- **Écart « en même temps » réglé trop haut** : deux vraies caisses d'un même cueilleur présentées à moins de cet
  écart au même lecteur donnent une seule lecture, au statut Cheat ; la seconde caisse n'est pas comptée.
- **Tag hors liste de référence** (spec `010`) et **lecteur d'enregistrement** (spec `003`) : inchangés ; ces
  lectures ne créent ni lecture ni statut.
- **Lecture déjà corrigée à la main** : si la lecture de la caisse a été corrigée à la main avant l'arrivée du
  second tag, elle garde le statut fixé à la main (spec `005`, FR-006) ; le second tag ne crée toujours rien.
- **Période sans anomalie** : la liste des anomalies est vide et le dit, sans erreur.
- **Statut levé par l'Administrateur** : une lecture Cheat ou Doublon / perdu repassée à Conforme ou Non conforme
  redevient une lecture ordinaire, que l'Opérateur et le kiosque peuvent à nouveau basculer. Un ancien
  Doublon / perdu compte alors comme une caisse, sans cueilleur (ligne « Non attribué »).
- **Trois tags ou plus d'un même seau lus en même temps** : toujours une seule lecture Cheat ; chaque tag
  supplémentaire lu dans l'écart est absorbé comme le second.
- **Deux caisses de cueilleurs différents qui se suivent de près** : deux lectures ordinaires, aucun Cheat.
- **Lectures antérieures à la mise en service** : elles gardent Conforme ou Non conforme ; les anciens rescans de la
  journée, enregistrés non conformes, ne sont pas requalifiés en Doublon / perdu et restent donc comptés dans les
  totaux des périodes passées.
- **Seau supprimé ou cueilleur supprimé** entre deux lectures (specs `006`, `001`) : les règles s'appliquent au tag,
  indépendamment du seau et du cueilleur.

## Requirements *(mandatory)*

### Functional Requirements

**Statut d'une lecture**

- **FR-001** : chaque lecture DOIT porter un statut parmi quatre : **Conforme**, **Non conforme**,
  **Doublon / perdu** (un seul statut), **Cheat**. Conforme et Non conforme viennent du verdict du lecteur ou d'une correction
  manuelle ; Doublon / perdu et Cheat ne sont attribués que par le système (FR-005, FR-007). Remplace la conformité
  binaire de la spec `004` (FR-006).
- **FR-002** : une lecture Doublon / perdu N'EST PAS une caisse : elle NE DOIT compter ni dans les totaux de lectures
  et de caisses du tableau de bord (synthèse, tableau par cueilleur dont la ligne « Non attribué », répartition par
  heure, spec `007`), ni dans les colonnes d'activité (spec `014`), ni dans le taux de conformité. Une lecture Cheat
  reste une caisse et compte avec les non conformes ; seules les lectures Conforme comptent comme conformes.

**Relecture ignorée (30 min)**

- **FR-003** : une lecture en production d'un tag déjà compté, arrivant moins de **30 minutes** (valeur par défaut,
  paramétrable) après la dernière lecture de ce tag, NE DOIT PAS être prise en compte : aucune lecture n'est créée,
  la lecture existante n'est pas modifiée et le lecteur reçoit une réponse normale, sans erreur. Le délai est
  glissant : il part de la dernière lecture du tag, lectures ignorées comprises. La règle vaut pour **tous les
  lecteurs** de production, pas seulement celui qui a compté le tag.
- **FR-004** : la règle du même passage (spec `004`, FR-008 : même lecteur, 10 s) DOIT rester inchangée et
  s'appliquer avant FR-003. Un tag enregistré à nouveau dans un seau (spec `003`) n'est plus « déjà compté » :
  FR-003 et FR-005 ne s'appliquent pas à son scan suivant.

**Doublon / perdu**

- **FR-005** : une lecture en production d'un tag déjà compté, non enregistré dans un seau depuis et hors des délais
  de FR-003 et FR-004, DOIT créer une nouvelle lecture au statut **Doublon / perdu**, sans cueilleur, quel que soit le
  verdict du lecteur et quel que soit le jour du comptage (le même jour ou un jour précédent). La lecture précédente
  reste inchangée. Remplace, dans la spec `004` (FR-009), le « rescan non conforme » du même jour et la lecture
  ordinaire d'un jour suivant ; la sortie du seau au comptage est inchangée.

**Cheat**

- **FR-006** : le système DOIT détecter deux tags différents **du même seau** lus par le même lecteur de production
  à moins de **2 secondes** d'écart (valeur par défaut, paramétrable), l'écart se mesurant entre la première lecture
  de chacun. Deux tags de seaux différents, ou lus par deux lecteurs, ne sont pas concernés.
- **FR-007** : dans ce cas, la caisse DOIT donner **une seule lecture**, celle créée par le premier tag, qui passe au
  statut **Cheat** quel que soit le verdict du lecteur et reste attribuée au cueilleur du seau ; le second tag ne
  crée aucune lecture, sort du seau et compte comme déjà scanné pour FR-003 et FR-005. Une lecture déjà corrigée à
  la main garde son statut. Remplace l'absorption sans signalement du second tag d'une caisse (spec `004`, FR-010).

**Paramètres**

- **FR-008** : le délai de relecture ignorée (FR-003) et l'écart « en même temps » (FR-006) DOIVENT être deux
  réglages que l'Administrateur consulte et modifie depuis un écran de l'application. Une modification DOIT
  s'appliquer aux scans suivants sans redémarrage ni nouvelle version, et NE DOIT PAS requalifier les lectures déjà
  enregistrées. Les valeurs initiales sont 30 minutes et 2 secondes ; la valeur zéro désactive la règle
  correspondante.
- **FR-012** : la consultation et la modification des réglages DOIVENT être réservées à l'Administrateur ; un
  Opérateur et le kiosque d'une ligne DOIVENT être refusés.
- **FR-013** : une valeur de réglage DOIT être un nombre entier positif ou nul, d'au plus 24 heures pour le délai de
  relecture ignorée (saisi en minutes) et d'au plus 60 secondes pour l'écart « en même temps » (saisi en secondes) ;
  toute autre saisie est refusée et la valeur en vigueur conservée. L'écran de réglages DOIT indiquer, à côté du
  champ de l'écart « en même temps », ses deux conséquences : à zéro, deux tags d'un même seau comptent pour deux
  caisses ; trop haut, deux caisses d'un même cueilleur qui se suivent n'en comptent qu'une, au statut Cheat.
- **FR-014** : le système DOIT conserver, pour chaque réglage, l'auteur et la date de sa dernière modification, et
  les afficher sur l'écran de réglages.

**Affichage et correction**

- **FR-009** : partout où la conformité d'une lecture est affichée (écran de la ligne, spec `005`), le statut DOIT
  l'être, les quatre statuts étant distinguables.
- **FR-010** : seul l'Administrateur DOIT pouvoir passer une lecture Doublon / perdu ou Cheat à Conforme ou Non
  conforme, avec la même traçabilité que la bascule de conformité actuelle (spec `005`) ; la même demande venant d'un
  Opérateur ou du kiosque de la ligne DOIT être refusée. La bascule entre Conforme et Non conforme reste ouverte à
  l'Opérateur, à l'Administrateur et au kiosque (spec `005`, FR-004). Les statuts Doublon / perdu et Cheat NE DOIVENT
  PAS pouvoir être attribués à la main.
- **FR-011** : le tableau de bord (spec `007`) DOIT donner, pour la période et le lecteur choisis, le nombre de
  lectures Doublon / perdu (à part, hors du total, FR-002) et le nombre de lectures Cheat, et le tableau par cueilleur ainsi que son export DOIVENT
  indiquer le nombre de lectures Cheat de chaque cueilleur. Une lecture Cheat DOIT rester comptée dans le total de
  caisses du cueilleur et dans la colonne de son activité (spec `014`) : la colonne Cheat s'y ajoute, elle n'en
  retire rien.
- **FR-015** : le système DOIT permettre à l'Administrateur de lister les lectures Doublon / perdu et Cheat d'une
  période et d'un lecteur choisis comme sur le tableau de bord (mêmes périodes, 31 jours au plus, spec `007`), les
  plus récentes d'abord, avec pour chacune sa date, sa ligne, son tag, son statut, son activité et son cueilleur s'il
  y en a un, et de passer chacune à Conforme ou Non conforme depuis cette liste (FR-010). Une lecture corrigée quitte
  la liste. La liste DOIT être réservée à l'Administrateur ; un Opérateur et le kiosque d'une ligne DOIVENT être
  refusés.

### Key Entities

- **Lecture** (spec `004`) : son verdict binaire devient un **statut** à quatre valeurs (FR-001). Les autres
  attributs (tag, lecteur, cueilleur, activité, date) sont inchangés.
- **Tag** (spec `004`) : le système connaît la date de sa dernière lecture, lectures ignorées comprises, pour
  appliquer le délai glissant de FR-003.
- **Réglages de scan** (nouveau) : deux valeurs modifiables par l'Administrateur — délai de relecture ignorée (30 min
  au départ) et écart « en même temps » (2 s au départ, jusque-là réglage de l'installation, désormais associé au
  statut Cheat) — chacune avec l'auteur et la date de sa dernière modification. Le délai du même passage (10 s, spec
  `004`) reste un réglage de l'installation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** : pour un jeu connu de scans couvrant les trois règles, 100 % des lectures obtenues (créées ou non,
  statut, cueilleur) correspondent au résultat attendu.
- **SC-002** : une caisse relue dans les 30 minutes suivant son comptage, par n'importe quel nombre de relectures,
  n'ajoute aucune lecture.
- **SC-003** : sur une journée de production, plus aucune lecture Non conforme ne provient d'un rescan : toutes les
  lectures Non conforme viennent du verdict d'un lecteur ou d'une correction manuelle.
- **SC-004** : un Administrateur identifie les cueilleurs ayant des lectures Cheat sur une période en moins d'une
  minute depuis le tableau de bord, et retrouve puis corrige une lecture Cheat de la veille en moins d'une minute.
- **SC-005** : les lecteurs obtiennent leur réponse aussi vite qu'aujourd'hui : 95 % des scans en moins de 200 ms
  avec 1 à 3 lecteurs actifs (spec `004`, SC-004). **Mesuré le 2026-11-15** (H2 en mémoire, 3 lecteurs en parallèle,
  900 scans par passe) : p95 = 10,3 ms pour des paires de tags d'un même seau (comptage puis Cheat), 7,0 ms pour leur
  relecture ignorée. À refaire sur PostgreSQL avec une table `record` de taille saison avant la mise en production.
- **SC-006** : l'Administrateur modifie une durée en moins d'une minute, et le premier scan qui suit l'enregistrement
  applique la nouvelle valeur.
- **SC-007** : sur toute période postérieure à la mise en service, le total de caisses du tableau de bord égale le
  nombre de lectures Conforme, Non conforme et Cheat ; aucune lecture Doublon / perdu n'y est comptée.

## Assumptions

- « En même temps » reprend l'écart de 2 s déjà utilisé pour deux tags d'une même caisse (spec `004`, FR-010) et le
  même lecteur ; deux lecteurs différents lisant deux tags au même instant ne sont pas un Cheat.
- Seules les deux durées de cette fonctionnalité sont réglables à l'écran ; le délai du même passage (10 s, spec
  `004`) reste un réglage de l'installation. Les maximums de FR-013 (24 h, 60 s) sont des garde-fous contre une
  erreur de saisie.
- Les lecteurs n'ont rien à changer : ils envoient les mêmes scans et reçoivent le même format de réponse ; une
  lecture Doublon / perdu ou Cheat leur est annoncée comme non conforme.
- Le statut Cheat est un signalement : aucune sanction automatique (retrait de caisses, blocage du cueilleur) n'est
  appliquée.
- Les heures de travail (spec `014`) et les activités (spec `012`) ne sont pas touchées ; une lecture Doublon / perdu
  ou Cheat porte l'activité en cours comme toute lecture.
