# UI contract: vocabulary « paquet »

What every page displays once the feature is done. Rule (research R2, R3): « seau » / « seaux » as a whole word
becomes « paquet » / « paquets », same case and number; « réseau » and « caisse » are never touched. Line numbers are
those of the branch before the change.

## Menu entry (10 pages)

`activities.html:52`, `anomalies.html:52`, `buckets.html:46`, `index.html:64`, `pickers.html:46`, `readers.html:66`,
`settings.html:49`, `tags.html:52`, `users.html:46`, `work-hours.html:52`

| Before                                        | After                                        |
|-----------------------------------------------|----------------------------------------------|
| `<i class="fas fa-fill me-1"></i>Seaux`       | `<i class="fas fa-box me-1"></i>Paquets`     |

Link target (`buckets.html`) unchanged.

## `front/buckets.html` (18 besides the menu)

| Line    | Before                                                         | After                                                            |
|---------|----------------------------------------------------------------|------------------------------------------------------------------|
| 6       | `Seaux - Vegelink`                                             | `Paquets - Vegelink`                                             |
| 64      | `Seaux`                                                        | `Paquets`                                                        |
| 65      | `Affectation des seaux aux cueilleurs`                         | `Affectation des paquets aux cueilleurs`                         |
| 76      | `N° du seau`                                                   | `N° du paquet`                                                   |
| 95      | `Affecter le seau`                                             | `Affecter le paquet`                                             |
| 128     | `Désaffecter le seau`                                          | `Désaffecter le paquet`                                          |
| 148     | `Supprimer le seau`                                            | `Supprimer le paquet`                                            |
| 167     | `Ce seau ou ce cueilleur n'existe plus.`                       | `Ce paquet ou ce cueilleur n'existe plus.`                       |
| 194     | `Impossible de charger les seaux.`                             | `Impossible de charger les paquets.`                             |
| 198     | `Erreur réseau lors du chargement des seaux.`                  | `Erreur réseau lors du chargement des paquets.`                  |
| 205     | `Aucun seau. Les seaux sont créés à l'enregistrement des tags` | `Aucun paquet. Les paquets sont créés à l'enregistrement des tags` |
| 268     | `Affecter le seau n°N`                                         | `Affecter le paquet n°N`                                         |
| 290     | `Seau n°N est actuellement affecté à …`                        | `Paquet n°N est actuellement affecté à …`                        |
| 291     | `Affecter le seau n°N à … ?`                                   | `Affecter le paquet n°N à … ?`                                   |
| 332     | `Retirer … du seau n°N ?`                                      | `Retirer … du paquet n°N ?`                                      |
| 363     | `Supprimer le seau n°N ?`                                      | `Supprimer le paquet n°N ?`                                      |
| 392     | `Ce seau n'existe plus.`                                       | `Ce paquet n'existe plus.`                                       |

## `front/tags.html` (13 besides the menu)

| Line | Before                                                                     | After                                                                        |
|------|----------------------------------------------------------------------------|------------------------------------------------------------------------------|
| 70   | `Enregistrement des tags sur un seau`                                      | `Enregistrement des tags sur un paquet`                                      |
| 71   | `… puis associez-les à un numéro de seau`                                  | `… puis associez-les à un numéro de paquet`                                  |
| 116  | `Numéro de seau`                                                           | `Numéro de paquet`                                                           |
| 134  | `Seau actuel`                                                              | `Paquet actuel`                                                              |
| 148  | `Tags déjà associés à un autre seau`                                       | `Tags déjà associés à un autre paquet`                                       |
| 152  | `… retirés de leur seau actuel et associés au seau N :`                    | `… retirés de leur paquet actuel et associés au paquet N :`                  |
| 312  | `Déjà sur ce seau`                                                         | `Déjà sur ce paquet`                                                         |
| 314  | `Seau N` (badge)                                                           | `Paquet N`                                                                   |
| 329  | `Saisissez un numéro de seau (entier à partir de 1).`                      | `Saisissez un numéro de paquet (entier à partir de 1).`                      |
| 351  | `… — le seau N contient maintenant X tag(s).`                              | `… — le paquet N contient maintenant X tag(s).`                              |
| 356  | `→ actuellement seau N`                                                    | `→ actuellement paquet N`                                                    |
| 366  | `Enregistrement refusé : vérifiez le numéro de seau …`                     | `Enregistrement refusé : vérifiez le numéro de paquet …`                     |

## Other pages

| File:line             | Before                                                                 | After                                                                    |
|-----------------------|------------------------------------------------------------------------|--------------------------------------------------------------------------|
| `index.html:208`      | `title="Caisses lues avec deux tags d'un même seau"`                   | `title="Caisses lues avec deux tags d'un même paquet"`                   |
| `pickers.html:243`    | `Ce cueilleur a encore un seau affecté : désaffectez-le avant …`       | `Ce cueilleur a encore un paquet affecté : désaffectez-le avant …`       |
| `settings.html:100`   | `Deux tags d'un même seau lus par le même lecteur …`                   | `Deux tags d'un même paquet lus par le même lecteur …`                   |
| `settings.html:105`   | `À 0, deux tags d'un même seau lus ensemble comptent pour deux caisses.` | `À 0, deux tags d'un même paquet lus ensemble comptent pour deux caisses.` |

## Unchanged on purpose

- Every « Erreur réseau » (contains « seau » as a substring only).
- « caisse(s) »: the unit counted by a scan (FR-009).
- URLs, ids, JS names (`bucketNumber`, `selectedBucket`…), API paths and payloads, `reader.html` (kiosk).
