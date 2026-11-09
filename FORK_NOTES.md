# Cassette Android — Fork Notes

Fork of **Tempo** (https://github.com/CappielloAntonio/tempo), the Android analog
to the iOS Amperfy fork. Subsonic-native client on Media3/ExoPlayer with
background playback, offline downloads, gapless, and Android Auto
(`MediaLibraryService` + `MediaBrowserTree`) already built upstream.

- **Fork point:** upstream `9cf62c8` (2025-01-31, "gradle: update gradle build tools"), Tempo 3.9.0 / versionCode 26
- **License:** GPLv3 (same copyleft accepted for the Amperfy fork). Distributing
  APKs (site beta, Play) triggers the source-availability obligation.
- **Flavor:** build the `tempo` flavor (FOSS). `play` (Cast) and `notquitemy` left untouched.

## Changed from upstream (as of 2026-07-11)

| Surface | Change |
|---|---|
| `app/build.gradle` | `tempo` flavor `applicationId` → `digital.cassette.player`; version reset → `0.1.0` / versionCode 1 |
| `res/values/strings.xml` | `app_name` → Cassette |
| `res/values/colors.xml` | Dark theme → Cassette palette (primary `#E87830`, bg `#1C1913`, surface `#221E17`, on-colors cream `#EDE4D0`); `ic_launcher_background` color added |
| `res/mipmap-*` | Cassette adaptive icon (cream reel mark on orange), **now emitted by the one-source icon generator** (`apps/cassette/scripts/icon/generate.mjs` → `ANDROID_RES`), incl. `ic_launcher_round` |
| `mipmap-anydpi-v26/ic_launcher.xml` | background layer → `@color/ic_launcher_background` |
| `util/Cassette.java`, `ui/activity/PairCallbackActivity.java`, manifest, `fragment_login.xml` | Cassette account pairing (sign in → `cassette://auth/callback` → `/api/player/me` → auto server row). Debug builds remap the hub to `10.0.2.2` on emulators. |
| `bottom_nav_menu.xml` | Tabs → Home / Library / **Search** (iOS parity); Downloads becomes a Library row |
| `HomeTabMusicFragment` | Home shelves = most played / recently played / new releases / recently added / pinned playlists. Starred×3, discover, similar, artist-radio, best-of, flashback, top songs, shares: init calls removed (sectors default `gone`) |
| `LibraryFragment` + `fragment_library.xml` | Genres hidden; Downloads door added |
| `Preferences.kt` | Podcast / Radio / music-directory sections default OFF (settings can re-enable) |
| `App.java` | Theme defaults to **dark** (Cassette is dark-first) |
| `util/CassetteSyncApi.java`, `service/CassetteSyncEngine.java`, `util/CassetteArtistArt.java` (new) | **Offline-first account sync** (2026-07-12): polls the cloud's per-device album intents, downloads through Tempo's Media3 stack at the account quality tier (lossless=raw `/download`, else `stream?format=mp3`), reports device inventory (`cassette_local_id`), syncs attributed artist photos to a local store (`CustomGlideRequest` prefers it). Convergent + idempotent; 30s foreground poll + queue-drain kicks. |
| `model/Download.kt`, `AppDatabase`, `DownloadDao` | Room v11 (auto-migration): `cassette_local_id` + `mbid` identity columns on downloads |
| `DownloaderManager`, `DownloaderService`, `DownloadUtil` | Tracker map fresh on every state change + pending-id set (bulk enqueue no longer re-sends parked work — Wi-Fi-off ANR fix); per-song success notifications dropped; downloads Wi-Fi-only; engine-level 1GB free-space floor pauses/resumes the queue (Media3's storage requirement proved toothless) |
| `res/xml/global_preferences.xml` | Stock transcoded-download prefs hidden (account owns the download tier) |

**Build hygiene:** `app/build` and `.gradle` are symlinks into `~/.cache/cassette-android/` — iCloud syncs `~/Documents` and its conflict-duplicates (" 2" files) inside build dirs break D8. Don't replace the symlinks with real dirs. A failed build leaves the previous APK in `outputs/` — only install after `BUILD SUCCESSFUL`.

Note: `namespace` stays `com.cappielloantonio.tempo` (R class / code packages
untouched) — only the applicationId is rebranded. Same pattern as shipping a
rebrand without a mass package rename.

## Owed (alignment with the iOS app)

1. ~~**Login → Cassette pairing**~~ DONE — account pairing + stable
   `cassette-<username>` server id shipped; manual add-server kept as dev door.
2. **Favorites decision** — ~15 files carry Tempo's star/favorite surface. iOS
   ripped favorites out entirely (web favorites live in the cloud DB and do NOT
   sync to Subsonic stars). Strip or rewire — do not ship both models.
3. **Restyle pass** — carousels/grids/settings/type toward the Cassette design
   system; strip last.fm-branded UI on album/artist pages (`AlbumPageFragment`,
   `ArtistPageFragment` render Navidrome ArtistInfo/last.fm links). NOTE:
   Subsonic `/rest/scrobble` reporting is **play counts to Navidrome — keep it**;
   it is unrelated to the retired Last.fm enrichment.
4. **Repo topology** — currently an untracked source drop in the monorepo (no
   nested `.git`, unlike the iOS/navidrome forks). Decide at first ship: nested
   repo with upstream remote (enables pulling Tempo fixes) vs monorepo-tracked.
5. **Sync follow-ups** — plays → `POST /api/sync/plays` (rows carry
   `cassette_local_id` now); artwork-manifest healing for already-owned albums;
   serverMode browse filter (non-serverMode should browse the downloaded set and
   never stream, like iOS); Downloads-screen re-diff perf under churn; tier
   changes don't re-transcode existing files.

### 2026-10-26 — Song sharing

**Type:** Java patch
**Files:** `util/CassetteShareSong.java` (new), `ui/fragment/bottomsheetdialog/ShareSongNoteBottomSheetDialog.java` (new), `util/CassetteSyncApi.java`, `SongBottomSheetDialog`, `PlayerControllerFragment`, manifest, `bottom_sheet_song_dialog.xml`, `inner_fragment_player_controller_layout.xml`

The iPhone's ShareSongSheet on Android, through `/api/share/song`. Share leads the
song menu (greyed with "No video to share yet" from the GET check) and Now Playing
gains a ⋮ that opens that menu. Share opens Android's own share sheet at once with a
phone-made `/s/<code>` link, the album cover as its preview (FileProvider) and Add a
note as a chooser action (Android 14+; the first target before that). The share saves
as the sheet opens; Android hands the link over at once, so a save that fails before a
pick closes the sheet, and one that fails after says the link won't open. Tempo's hub
share in the song menu is replaced.

**Build:** `app/build` is a real folder inside iCloud again (iCloud turns the symlink
back into one), and iCloud locks files in it mid-build. Build with output outside it:
`./gradlew -I outside-icloud.init.gradle assembleTempoDebug` (APK lands in
`~/.cache/cassette-android/out/app/outputs/apk/tempo/debug/`).

### 2026-07-26 — Cloud playlist cutover + Saved mirror

**Type:** Java/Kotlin patch
**Files:** `util/CassetteSyncApi.java`, `repository/PlaylistRepository.java`, `repository/FavoriteRepository.java`, `repository/SearchingRepository.java`, `viewmodel/HomeViewModel.java`, `database/dao/PlaylistDao.java`

Playlists for the Cassette UX now read and write `user_playlists` via
`/api/sync/playlists` (same cloud store as the web and Mac card).
`PlaylistRepository` no longer lists Subsonic hub playlists except for a
one-shot import. Home Playlists shelf shows the full cloud list (pin
intersection dropped). Track stars also mirror into cloud Saved. Search
suggestions include cached cloud playlist names.

## Build

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
# local.properties: sdk.dir=/opt/homebrew/share/android-commandlinetools
./gradlew assembleTempoDebug
# → app/build/outputs/apk/tempo/debug/app-tempo-debug.apk
```

## Emulator (local testing, no device)

```
avdmanager create avd -n cassette -k "system-images;android-35;google_apis;arm64-v8a" --device pixel_7
$ANDROID_HOME/emulator/emulator -avd cassette
adb install -r app-tempo-debug.apk
```

From inside the emulator, the Mac's localhost is **`http://10.0.2.2`** — log in
against the local hub at `http://10.0.2.2:4533` with the Navidrome admin
credentials from `apps/cassette-player/.env`. Emulator is Google-stock Android:
fine for UI/flow, useless for OEM background-kill validation (needs real
Samsung/Xiaomi/OnePlus hardware).

### 2026-11-09 — Catch-up with the iPhone, pass 1: stop the harm

**Type:** Java/Kotlin patch
**Audit:** monorepo `docs/CASSETTE_ANDROID_PARITY_AUDIT_2026-11-09.md` (BUG-936..948)

- **Playlist editor sends only the edits made** (BUG-936). `PlaylistRepository.getPlaylistEditorRows` ties each row on this phone to its cloud item; `savePlaylistEdits` sends a rename, the removed items and `reorder` ops that reuse the places the on-phone songs already held. Songs that live only on the computer or another phone are never touched. The old remove-all-then-re-add save and the unused index-based one are gone.
- **One on-phone rule for every play path** (BUG-939, BUG-942). `util/CassetteOnPhone` decides what can play; `MediaManager.startQueue/enqueue/init` queue only those songs, save that same list for the queue page and mini player, and start on the tapped song's place in it.
- **Resume carries on with the record** (BUG-943): `MediaManager.resume` continues the loaded queue at the saved place, else plays the song's record from that song (`DownloadDao.getAlbumSync`).
- **Sign Out** (BUG-937): asks Keep / Remove / Cancel about the music, calls `POST /api/player/sign-out`, forgets the account and the saved Cassette server (`util/CassetteSession`). A 401 from the cloud shows "Signed out" with Sign In (BUG-946).
- **Instant mix, the heart and Rate hidden** in the song, album and artist sheets; the Now Playing cover overlay removed (BUG-947).
- **Home's Albums row is newest added first**, not A to Z (BUG-944).

### 2026-11-09 — Catch-up with the iPhone, pass 2: the design floor

- **Colour roles from the kit, light and dark.** `res/values/colors.xml` (light paper) and `res/values-night/colors.xml` (dark) hold the kit's roles (`cs_bg`, `cs_raised`, `cs_elevated`, `cs_ink`, `cs_ink2`, `cs_ink3`, `cs_fill`, `cs_accent`...), the iPhone's values. The stock Material purple light palette and the July de-saturated dark one are gone; Tempo's colour names (`titleTextColor`...) alias the roles.
- **One DayNight theme** (`values/styles.xml`; `values-night/styles.xml` removed). Primary is ink, not orange: orange (`cs_accent`) is used by name for live state only. Auto follows the phone (default), Light and Dark in Settings > Appearance.
- **Account skins off** on Android (founder call); **round controls** (`Cassette.Shape.Button` 50%); **Android's own selected-tab pill** restored.
- **Kit:** `generate.mjs` now writes `Opacity` and `Component` (heading, playDisc, pill, tile, hero...) to `digital/cassette/kit/CassetteKitTokens.kt`.
- **New shared pieces:** `util/CoverTint.kt` (the record's colour, CoverTintMath ported from iOS), `ui/view/CoverGlowView.kt` (tiny pre-blurred cover + scrim, PAGE / RESUME_CARD / TOP_RESULT recipes), `util/CassetteType.kt` (SHOUT *answer*), `font/newsreader` (Newsreader Medium Italic, OFL, the kit's stand-in for New York), `Cassette.Role.*` type roles, `Cassette.Pill.Play` / `.Shuffle`, `layout/view_play_disc.xml`, `layout/view_empty_state.xml`.

### 2026-11-09 — Catch-up with the iPhone, pass 3: records and the player

- **Album / artist / playlist pages** rebuilt in the iPhone's lit room (see the commit for the detail): `fragment_album_page.xml`, `fragment_artist_page.xml`, `fragment_playlist_page.xml`, `CassetteRecordTileAdapter` (MORE BY / ALBUMS tiles with the play disc), `SongHorizontalAdapter` tint + rank mode + tight record rows; record pages read the phone's own store in offline-first (`DownloadDao.getAlbumSync/getArtistSync`).
- **Now Playing** (`inner_fragment_player_controller_layout.xml`): the cover's coloured room (`CoverTint.room`) + `CoverGlowView` ROOM recipe under glass of the page's paper, brighter while playing; caps title (marquee); artist in Newsreader italic in the record's colour; a glass ••• in the title row; a 4dp scrubber in the record's colour; Previous, a 78dp cream disc (`exo_controls_play/pause` overridden with Cassette's glyphs), Next. Shuffle and repeat moved to the queue's head; the extra Add-to-playlist key, the cover overlay and the sideways lyrics page are gone (lyrics are off, as on the iPhone). The cover opens Up Next. `CoverTint.setNowPlaying` tells the rest of the app which record is playing.
- **Queue**: UP NEXT *album* (two voices, record colour) with shuffle and repeat (orange only while on); played rows fold away; no floating shuffle button or "Clean play queue".
- **Mini player**: a floating rounded card above the tabs (`cs_mini_player_card`), 44dp touch targets with spoken names, a 3dp progress line in the record's colour; the sheet's peek grew to 64dp.

### 2026-11-09 — Catch-up with the iPhone, pass 4: Home from the cloud (One Home)

- `util/CassetteHome.kt`: `GET /api/sync/home?tz=` (shape: monorepo `apps/cassette/src/lib/home/types.ts`), the last answer kept in `files/cassette_home.json` so Home opens at once and offline, asked at most once a minute (Home open, foreground, pull to refresh). Resolved on the phone: records and artists by any of their songs' Cassette ids (`DownloadDao.getByCassetteLocalIds`), made-for-you playlists by their songs here (Lately with any, the rest with 15+), your playlists only when one of their songs is here. Stations and All shelves wait for their passes.
- Home (`HomeTabMusicFragment`): the clock greeting is gone; the cloud's rows draw in its order with two-voice headings (orange answers, Home only) and `CassetteTileAdapter` tiles with the play disc; today's Recent / Albums / Artists / Playlists rows stay only as the stand-in when no answer is held; the cloud's Resume leads the Resume card; the sync banner no longer hides Resume.
- `MixPageFragment`: a made-for-you playlist's page (picture, caps name, subtitle in the serif, count and length, pills, songs).
- `POST /api/sync/playlists/{id}/played` when a playlist starts (`PlaylistRepository.notePlayed`), so every device's Playlists row and Resume know.
- Note: like the iPhone, each Home fetch records what the reaching row showed (`user_home_shown`) on the cloud.

### 2026-11-09 — Catch-up with the iPhone, pass 5: Search and Library on the phone

- **Search reads the phone** (BUG-940): `util/CassetteSearch.kt` matches what is on this phone as you type, no network (songs on title, artist or album; albums on name or artist; artists on name; names starting with what was typed lead). `fragment_search.xml` / `SearchFragment` rebuilt as Cassette's own screen, Android's resting search tab (the keyboard comes up when the field is tapped): one lit top card that plays at once (artist, else album), SONGS, ALBUMS and ARTISTS tiles with the play disc, your PLAYLISTS by name; "No results on this phone" when nothing matches; recent searches before typing; coming back keeps the results.
- **Library**: a "139 ALBUMS" / "N ARTISTS" / "N PLAYLISTS" count line; one plain empty title; the grid fills in as music arrives (watches the phone's songs, no flash); playlists show the last good list offline instead of an empty page until restart (BUG-948); playlist rows read "9 songs" (no "0:00"), lose the ⋮ (long-press still edits) and sit on the page's gutter.

### 2026-11-09 — Catch-up with the iPhone, pass 6: Keep playing

- `util/CassetteKeepPlaying.kt`: the switch (on by default, `cassette_keep_playing`); as the last song of the queue starts (`MediaService.onTracksChanged` → `MediaManager.continuousPlay`), it sends the queue as Cassette song ids to `POST /api/sync/keep-playing` (one contract with the iPhone and desktop: monorepo `lib/keep-playing/contract.ts`) and appends the answer, only songs on this phone: a record in disc/track order, or songs in the cloud's order. Repeat wins (the key waits, faint, while repeat is on; pressing it turns repeat off). Offline, a record carries on with another record by the same artist on the phone.
- Tempo's continuous play (an instant mix from the computer, empty with its outside lookups off) is gone.
- The queue's head gains the ∞ key (orange while on) and a "THEN: <the cloud's words>" line once a pick is queued.

### 2026-11-09 — Catch-up with the iPhone, pass 7: Settings and the account

- **Settings answers on sight** (`res/xml/global_preferences.xml`, `SettingsFragment.fillAnswers`): your name and email (the Account screen keeps Name, Email, Sign Out); YOUR COMPUTER *in sync / syncing / out of reach* with the computer's name, "Synced N min ago" and Resync Library; THIS PHONE *N albums* with the music's size; Download quality (read-only, one plain value), Equalizer, Appearance; Help, Report a problem; Storage; About (version and the Tempo / GPL v3 credit with the source link). The Playback screen is gone; no dividers.
- `util/CassetteComputer.kt`: the computer's one-word state, the iPhone's rule ported (heartbeat from `GET /api/player/devices/mac` within 15 minutes = in sync; never "in sync" without one), asked when Settings or the account sheet opens, never polled. Its name comes from its address; never "Mac" as the general word.
- **Account sheet** from the avatar (`sheet_account.xml`, `ToolbarFragment`): you and "<computer> · in sync", Settings, Sign Out apart in red.
- **Remove from This Phone** on an album's menu: confirm ("It stays on your computer."), `POST /api/sync/marks/exclude` so the cloud keeps it off this phone, then the files go; offline it says so and removes nothing (otherwise the next sync would bring it straight back).
- **Words**: "Sign in with Cassette", "Signing in…", "Signed in. Your computer isn't reachable on this network yet.", "Couldn't sign in. Try again."

### 2026-11-09 — Catch-up with the iPhone, pass 8: Radio, the minimum lane

- **Library > Radio** (after Playlists): the whole dial on every phone, not only the stations a computer holds (iPhone e0b0efab). `util/CassetteRadio.kt` reads `assets/radio-catalog.json` (a copy of monorepo `apps/cassette-player/scripts/radio-catalog.json`, the file the iPhone bundles) and refreshes it from the public `GET /api/radio/catalog` once a launch, kept in `files/radio-catalog.json`. Grouped by the registry's sections; "120 STATIONS" on the count line.
- Each station plays straight from its own stream (`MediaManager.startRadio`); no computer involved. Rows: a squircle (`CassetteStationShape`, the kit's 22.5%) of the station's picture, or its name as word art on its own colour; the station on air in orange. A long press: Play, Go to <site>, Support <station>; never Tempo's station editor.
- `MappingUtil.mapInternetRadioStation`: a station is never its stream address. The title is left empty so the stream's own title (the song or show) fills it; the station's name is the second line and its picture the artwork. The mini player shows no Next for a station.
- Not yet: what's on now (`/api/radio/now-playing`, `/on-now`), Save (`/api/radio/saved`), station pages, stations in Search and on Home.

### 2026-11-09 — Catch-up with the iPhone, pass 9: Android Auto plays the phone's copies

- `MediaBrowserTree.getItems` (BUG-941): when the car taps a song, the queue rebuilt from the stored list keeps only songs on the phone, from the one tapped on, each pointed at the phone's own copy (`MappingUtil.getDownloadUri`, now public). It used to carry the computer's streaming address for every song.
- Not yet: the car's lists still come live from the computer (Tempo's tree); Home in the car does not draw the cloud's Home; no Play/Shuffle row on album lists; radio in the car still lists the computer's stations.
