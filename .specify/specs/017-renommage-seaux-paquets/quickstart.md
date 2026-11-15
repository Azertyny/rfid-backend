# Quickstart: validate the « paquet » vocabulary

## 1. Automated checks

```zsh
# SC-001: no whole-word « seau(x) » left (« réseau » is not matched). Expected: no output.
perl -CSD -ne 'print "$ARGV:$.: $_" if /(?<!\p{L})[Ss]eaux?(?!\p{L})/; close ARGV if eof' \
  front/*.html front/*.js src/main/resources/openapi/api.yaml doc/20251116-use_cases.md

# Only the glossary note remains in CLAUDE.md. Expected: one line, « formerly seau ».
grep -n 'seau' CLAUDE.md

# Old menu icon gone, new one on every page. Expected: 0 then 10.
grep -c 'fa-fill' front/*.html | awk -F: '{s+=$2} END {print s}'
grep -l 'fa-box me-1"></i>Paquets' front/*.html | wc -l

# SC-002: behaviour unchanged.
mvn clean test
```

## 2. Manual pass

Run the app (`APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run`), open
the front, log in as Administrateur; compare each text with [contracts/ui-vocabulary.md](contracts/ui-vocabulary.md).

1. **Menu** (User Story 3): on every page, the entry reads « Paquets » with a box icon and opens the assignment page.
2. **Assignment page** (User Story 1): tab title « Paquets - Vegelink », heading, subtitle, column « N° du paquet ».
   Assign, reassign, unassign then delete a paquet: each window title and question says « paquet n°N ». With an
   empty database, the empty message says « Aucun paquet. Les paquets sont créés… ».
3. **Tag registration** (User Story 2): heading, help and field say « paquet ». Read tags, register them on a new
   number (success message « le paquet N contient… »), read them again and register them on another number (badge
   « Paquet N », window « Tags déjà associés à un autre paquet »), submit an empty number (« Saisissez un numéro de
   paquet »).
4. **Other texts** (User Story 3): settings page, Cheat gap help; dashboard, tooltip of the Cheat link; pickers page,
   delete a picker with a paquet assigned (« … encore un paquet affecté … »).
5. **Unchanged** (FR-007, FR-009, SC-003, SC-004): existing bucket numbers, assignments and dashboard totals are the
   same as before; « caisses » still names the counted units; a bookmark to `buckets.html` works; a reader scan and
   the line kiosk work without reconfiguration.
6. As Opérateur: the menu shows only « Dashboard » (no « Paquets » entry, spec `008`), and the dashboard's Cheat
   link tooltip says « paquet ».
