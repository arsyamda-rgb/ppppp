# VeyrionWardrobe: design and status

## Verified (not guessed)
* SkinsRestorer 15.12.6: API read from the jar you uploaded (javap). Variants are `CLASSIC` and `SLIM`; "WIDE" in the GUI maps to `CLASSIC`.
  Flow for a URL skin: `SkinStorage.findSkinData(url, variant)` (cache) -> else `findOrCreateSkinData(url, variant)` ->
  `PlayerStorage.setSkinIdOfPlayer(uuid, identifier)` -> `getSkinApplier(Player.class).applySkin(player, property)`.
* VeyrionCharacter (this repo's updated copy): `has`, `withdraw`, `previews()` added, additive, API_VERSION still 1.
* ItemsAdder 4.0.3: NOT verified (no jar). `FurnitureInteractEvent`, `FurnitureBreakEvent` and `ItemsAdderLoadDataEvent` exist in the
  4.0.10 javadoc; check them against 4.0.3 when the jar is on the build path. No ItemsAdder code is written yet.

## Stages
1. DONE  skeleton, dependency guard, settings, SQLite storage, OutfitManager
2. DONE* SkinsRestorerIntegration + SkinManager (async processing, cooldown, timeout, cache, apply, preview refresh)
         *the SkinsRestorer adapter still has to be checked against the jar (see "Stage 2: open verification")
3.       ItemsAdder: AetherMirrorManager/Listener, permissions, /wardrobe commands, restore mirrors after ItemsAdderLoadDataEvent
4.       WardrobeMirrorPreview (VeyrionCharacter previews API), WardrobeGUI (ItemsAdder Custom GUI), chat input for URL
5.       Paid features (only when you decide a price): MoneyIntegration on top of VeyrionCharacterAPI.withdraw

## GUI layout (placeholder textures; right-side panel like GTA)
The ItemsAdder Custom GUI is a texture drawn over a chest inventory, and that inventory is always centred on the screen.
To get a right-hand panel: use a 6-row chest, draw the panel texture only over columns 6-9 (shifted with a negative-offset
font image), keep columns 1-5 empty and transparent so the mirror and the 3D preview stay visible on the left, and put every
button in columns 6-9. Not yet tried in ItemsAdder 4.0.3: it must be tested in game before the layout is final.

```
 col: 1 2 3 4 5 | 6 7 8 9
 row1:  (clear)  | title bar            "AETHER MIRROR"
 row2:  (clear)  | My Outfits  / Add Outfit
 row3:  (clear)  | Current Skin / Settings
 row4:  (clear)  | outfit list (page)
 row5:  (clear)  | outfit list (page)
 row6:  (clear)  | < page   Close   page >
```
Black (#0b0b0f) panel, gold (#c9a227) frame and text, thin gold dividers; no extra ornaments.

## Rules this code already enforces
* An outfit belongs to the player who USES the mirror, never to the mirror owner (OutfitManager takes the user's UUID only).
* Limit, unique name (case-insensitive) and owner check are done in one SQL step each, so two requests cannot break them.
* Names: letters, digits, space, `_ - ' .`; no `<`, `>`, `§`.
* Skin URLs: https only, no credentials, no private/loopback hosts, optional host allow-list.
* Database: WAL + synchronous FULL + quick_check at start. A broken file is left untouched and the plugin disables itself.

## Stage 2: how it works
```
addOutfit(player, url, model, name)
  validate URL (text) -> name/limit pre-check (no MineSkin use for a request that cannot be saved)
  -> resolve:  cache hit?  yes: backend.lookup (SkinsRestorer's own storage, no network)
                           no / forgotten: [cooldown] [limit] image probe -> backend.create (findOrCreate, network)
                           same key already running: join that flight
  -> main thread: outfits.add (saved with skinRef) -> backend.apply (setSkinIdOfPlayer + applySkin) -> outfits.setActive
                  -> afterApply: SkinPreviews.refresh (after preview.refresh-delay-ticks)
  apply fails -> the new outfit is deleted again (nothing saved, old outfit stays active)
```
* Threads: SkinManager's state is touched on the main thread only; the worker thread gets plain values and returns one value. No locks.
* Timeout is per player; the shared flight keeps going and fills the cache, so "retry" after a TIMEOUT is usually instant. A flight that never ends is dropped after 3x the timeout (frees the slot).
* Cooldown/limit count real processing only (a cache hit or joining a running flight costs nothing). A failed processing still starts the cooldown (MineSkin is rate limited).
* Disconnect: the request ends with PLAYER_OFFLINE, nothing is applied or saved, the skin stays cached.
* Why `reapply-active-on-join` is off: SkinsRestorer remembers the player's skin by itself (`setSkinIdOfPlayer`).

## Stage 2: open verification (do this before trusting the build)
The SkinsRestorer 15.12.6 jar was NOT in the upload when Stage 2 was written, so `SkinsRestorerIntegration` could not be compiled against it.
The flow comes from the Stage 1 notes above. The names below are NOT recorded anywhere and are marked `// VERIFY` in the code:
* `net.skinsrestorer.api.property.SkinVariant` (package) with `CLASSIC` and `SLIM`
* `net.skinsrestorer.api.property.InputDataResult` (package and name) with `getIdentifier()` and `getProperty()`
* `findSkinData(String, SkinVariant)` and `findOrCreateSkinData(String, SkinVariant)` return `Optional<InputDataResult>`
* `PlayerStorage.setSkinIdOfPlayer(UUID, <type of getIdentifier()>)` and `SkinApplier.applySkin(Player, <type of getProperty()>)`
Check with (JDK needed):
```
javap -cp SkinsRestorer.jar net.skinsrestorer.api.storage.SkinStorage net.skinsrestorer.api.storage.PlayerStorage net.skinsrestorer.api.SkinsRestorer
javap -cp SkinsRestorer.jar net.skinsrestorer.api.property.SkinVariant net.skinsrestorer.api.property.InputDataResult
unzip -l SkinsRestorer.jar | grep -i "api/.*\.class" | grep -i "SkinApplier\|SkinIdentifier\|SkinProperty"
```
Also unknown: whether `applySkin` must run on the main thread (it is called there) and how many ticks until the server's profile of the player shows the new skin (`preview.refresh-delay-ticks`, default 2: if the mirror still shows the old skin, raise it).
`dev-checks/stubs-unverified-sr/` is a stand-in written from memory so the code can compile here; it proves nothing about the real jar.

## CharacterPreview (VeyrionCharacter Stage 6) as read
`previews()` returns `Optional<CharacterPreviewFactory>` (empty without ProtocolLib). `CharacterPreview.refreshSkin()` rebuilds the packet double from the subject's live profile (`WrappedGameProfile.fromPlayer`), re-shows it to the same viewers, and removes the preview itself if the subject is offline. All methods are idempotent. SkinManager never creates a preview: Stage 4's mirror creates it and calls `SkinPreviews.track(preview)`; tracking a second one for the same player removes the first.
