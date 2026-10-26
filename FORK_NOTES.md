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
