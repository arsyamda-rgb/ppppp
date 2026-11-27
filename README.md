# VeyrionWardrobe (Stage 2)

Build order:
1. In the VeyrionCharacter project: `mvn install` (it has has/withdraw/previews).
2. Here: `mvn package`. The jar is `target/VeyrionWardrobe-0.2.0.jar`. Put it in `plugins/` next to VeyrionCharacter, ItemsAdder 4.x and SkinsRestorer 15.x.

If one of the three is missing or the wrong version, the plugin disables itself and prints why (see `DependencyRules`).

## Stage 2: what is new
* `skin/SkinManager`: add outfit from a URL (`addOutfit`), wear a saved outfit (`wear`), re-apply the active one (`reapplyActive`).
* `skin/SkinsRestorerIntegration`: the only class that touches the SkinsRestorer API. **Read the VERIFY notes in docs/DESIGN.md before the first `mvn package`.**
* `skin/SkinCache` + table `skin_cache`: processed skins survive restarts (size cap + expiry).
* `skin/HttpSkinImageProbe`: checks the image (reachable, PNG, 64x64 or 64x32, size) before SkinsRestorer gets it.
* `skin/SkinPreviews`: the mirror (Stage 4) hands over its `CharacterPreview`; a new skin is redrawn with `refreshSkin()`.
* New config keys: `skins.max-concurrent`, `skins.probe-image`, `skins.max-image-kb`, `skins.reapply-active-on-join`, `skins.cache.*`, `preview.refresh-delay-ticks`. An old config.yml keeps working (defaults are used); delete it once to get the commented new one.
* Old databases are upgraded by themselves (`CREATE TABLE IF NOT EXISTS skin_cache`).

Checks (no Paper needed; commands in dev-checks/README.txt):
* `WardrobeCheck`: 100 checks (Stage 1 rules).
* `SkinCheck`: 249 checks (Stage 2: URL, failures, timeout, cache, concurrency, cooldown, disconnect, apply, restart, previews, PNG, HTTP).
* `sql_check.py`: 55 checks of the real SQL against SQLite, including the Stage 1 -> Stage 2 database upgrade.
* NOT proven here: `SkinsRestorerIntegration` against the real jar, the plugin on Paper, the SQLite JDBC driver. Only `mvn package` and a server test prove those.
