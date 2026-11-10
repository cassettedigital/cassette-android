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

### 2026-11-09 – Catch-up with the iPhone: Genres

- **Genres of the music on this phone** (iPhone LibraryGenres 9f605fd1 + SearchStartView GENRES): `util/CassetteGenres.kt`. A record's genre is its files' own tag, else the cloud's (`GET /api/sync/album-genres`, your own genre, else the catalog's), keyed by the album's group key. Asked at most every 12 hours; the answer is kept on the phone (`files/album-genres.json`) so Genres works offline. Spellings that differ only in case are one genre.
- **Group-key gotcha**: the cloud's stored keys join artist and album with U+001F (Postgres text refuses NUL, `library-group-keys.ts` persistableGroupKey); the phone's `CassetteAlbumGroupKey` joins with NUL. `CassetteGenres` folds U+001F back to NUL on read. Without it only the records with file tags got a genre (4 of 163 on the founder's phone; 145 with it).
- **One tile, two places**: `item_genre_tile.xml` / `CassetteGenreTileAdapter` (104dp, the record's hue at the shared soft tone via CoverTint.tileFill / tileInk / tileCount, the record tipped 14° into the corner, the name over it, the bare count). Library > Genres (after Radio) shows them two to a row; Search's start page shows GENRES first, then RECENT, as the iPhone does. A zero-width space after "-" and "/" lets SINGER- / SONGWRITER wrap at the hyphen; Android would otherwise split mid-word.
- **Genre page**: `GenrePageFragment` / `fragment_genre_page.xml`, the name big in caps, "3 ALBUMS · 44 SONGS", PLAY and SHUFFLE over every song of it on the phone, then its records in the Library grid.

### 2026-11-09 – Catch-up with the iPhone: MORE LIKE

- **MORE LIKE on album, artist and playlist pages** (iPhone AlbumDetailVC / ArtistDetailVC / PlaylistDetailVC, founder 2026-10-21): other records on this phone whose songs carry the same moods and sound, one per artist, at most eight, each opening its record or playing it in one tap. An album's shelf leaves out its own artist and Various Artists; an artist's shows each other artist by their record with the most tags; a playlist's leaves out the records it already draws from.
- **The matching is the iPhone's**: `util/CassetteLikeness.kt` ports ListeningShelves' tag vectors, mood/sound likeness, recordBar (mood 0.45, sound 0.25, score 0.55, else the mood alone at 0.75 / 0.7), moreLike, moreLikeArtist and the interlude rule (BUG-496), with keys summed in order and scores rounded to nine places (BUG-494). `CassetteLikenessTest` covers it (7 tests).
- **The tags**: `util/CassetteMoods.kt` reads `GET /api/sync/library-moods` (each song's moods, energy, valence, sound and scenarios by cassette_local_id), at most every 12 hours, kept in `files/library-moods.json` so the shelves work offline. A record's artist is its dominant track artist with "feat." stripped.
- **One shelf**: `view_record_shelf.xml` + `ui/adapter/CassetteShelf.java` hold the heading (MORE LIKE *name* in the page's colour), the tiles, what a tile does and the off-main-thread fill, so the three pages cannot drift apart.
- **Album page fix**: the song list was built once per fragment, so coming Back to an album (after opening another from a shelf, say) showed no songs; and rows built from the first copy of the record kept its artist context, so Dookie opened from a shelf printed "Green Day" under every song. The list is now rebuilt with the view and follows the record's artist (`SongHorizontalAdapter.setContextArtist`).

### 2026-11-09 – Catch-up with the iPhone: This phone

- **This phone page** (iPhone ThisPhoneView, e080d4c0): Settings > THIS PHONE > Albums on this phone. "ON THIS PHONE", "163 albums", "1,999 SONGS · 11 GB"; a card with Download quality and Free on this phone; KEPT OFF *this phone*; BIGGEST *first*. `ui/fragment/ThisPhoneFragment.java`, `fragment_this_phone.xml`, `item_phone_album.xml`, `item_kept_off_tile.xml`, `drawable/cs_card.xml`, `ic_lucide_smartphone.xml`.
- **Sizes are what is stored**: each song's bytes come from Media3's download index (`getBytesDownloaded`), the server's `size` only when unknown. The hub's `size` is the original file's (FLAC), while the phone keeps the account's tier (MP3 on High), so the server figure overstated records about five times.
- **KEPT OFF** reads this phone on the cloud fresh each time the page opens (`GET /api/player/devices/{id}`, `keptOffAlbums` with `excludeTargetIds`; `CassetteSyncApi.getThisDevice`). A tap puts the record back (`DELETE /api/sync/marks/exclude` with `target_ids`; `CassetteSyncApi.putBack`), hides it until a fresh read speaks for it, and reports the phone's inventory at once (`CassetteSyncEngine.reportInventoryNow`) so the cloud queues it again. "Couldn't put it back." on failure.
- **Remove**: long-press a record in BIGGEST (Android's convention for the iPhone's swipe) for the album menu's confirm, word for word. The removal itself now lives once in `CassetteOnPhone.removeAlbum` (keep-off first, files only once the cloud has it, offline says so), and the album menu calls it too.
- Not exercised on the founder's phone: Put back and Remove (nothing was kept off, and removing a record to test would change their library).

### 2026-11-09 – Catch-up with the iPhone: Android Auto

- **The car's four tabs** (iPhone CarPlay, One Home 10-28): Home, Albums, Artists, Playlists, every one holding only what is on this phone. `util/CassetteCar.kt` builds them; `MediaBrowserTree` roots at them and hands its lists over (Tempo's Home / Library / Other browsed the computer's whole library, its folders and podcasts, none of which play away from home).
- **Home** is the cloud's Home (`CassetteHome.rowsNow`, the held answer resolved on the phone): the same rows, order and names as the phone, each holding what the phone holds. Resume rides as the first record of On repeat; Radio stays on the phone. Rows open to records (then their songs), made-for-you playlists (their songs) and your playlists.
- **Albums** A to Z (163 on the founder's phone), **Artists** A to Z by name with "feat." set aside (111), each opening their records here. A record's songs are the phone's own copies; a tap plays from that song on (the session list, BUG-941's path).
- **Playlists** come from the cloud (`/api/sync/playlists?include=items`), each with its songs on this phone in order; the last list is kept in memory while the app runs, for when the cloud can't be reached. The computer doesn't know the cloud's playlist ids ("playlist not found"), and Tempo's `getPlaylistSongs` never answers in that case, so the car would have spun forever on Home's Playlists row; the car no longer goes that way.
- **Search** in the car is the phone's own (CassetteSearch): records, then songs.
- Checked on the founder's Pixel 8 by building every list on the device from a temporary hook (removed): 4 tabs, 8 Home rows, Lately 40 songs, Chillax 9 songs, 163 albums, 111 artists, search. Not checked in a real car or the desktop head unit.

### 2026-11-09 – Catch-up with the iPhone: radio, what's on, Save and station pages

- **What's on, everywhere at once** (iPhone 96ad8657, 8dcd3464, f3a3c506): `util/CassetteRadioLive.kt`'s `Follower`, attached to the ExoPlayer in `MediaService`, asks the cloud what a playing station is on (`GET /api/radio/now-playing?station=`, public) every 30 seconds while it plays, and reads the stream's own title (ICY) split into song and artist, dropping a title that is only the station's name (BUG-758). It writes the song, artist, the picture (the record we carry, else the station's cover for the song, else the show's, else the station's own) and "Show · Until 3:00 PM, then X" onto the playing item with `replaceMediaItem`. The stream address is unchanged, so Media3 updates in place without a restart (read in media3-exoplayer 1.5.1 `ProgressiveMediaSource.canUpdateMediaItem`). The player, mini player, lock screen and car all read that item, so none of them had to learn about radio. Our own update comes back as a playlist change on the same station and is ignored. Pure rules unit-tested (`CassetteRadioLiveTest`, 5 tests).
- **The player on a station**: the station's name over the song (opens its page), the show and "Until 3:00 PM, then X" under the artist, "● LIVE" with the live dot in place of the scrubber, Save left of Stop and Go to album right of it (our copy when we carry it, else its page on Cassette; greyed when the song matched nothing).
- **Save** (iPhone 535f7b63): `util/CassetteRadioSaved.kt`, the web's one save: `GET /api/radio/saved`, `POST` / `DELETE /api/follows` (radio_station), kept on the phone latest first, changed at once and undone if the cloud refuses. YOUR STATIONS lead the dial; Save sits on the dial's long-press, the station page and the player.
- **Station pages** (iPhone RadioStationVC): `StationPageFragment` from `GET /api/radio/station/<slug>`: the picture lit like a record page, NAME, PLAY (STOP while on) and SAVE, ON AIR (the show with "Until … then …", the song on now in orange, what came before "12 min ago"), MORE LIKE *station*, read every 30 seconds while up. A song we carry opens its record here, else its Cassette page; long-press copies it. The "…" has the site and Support. A station tile anywhere (the dial, Search, Home, MORE LIKE) opens its page; its disc tunes in.
- **One station tile**: `CassetteTileAdapter.Tile.station`, with word art on the station's own colour when it has no picture (Radio Nova was a blank square). `Station.accentColor()` is the one colour rule for the dial, tiles and pages.
- **Sign-out forgets the account's data on the phone**: the held Home, album genres, song tags and saved stations (they belonged to the account and outlived it).
- Checked on the founder's Pixel 8: YOUR STATIONS (their two saves), KEXP's page, MORE LIKE with word art. Not checked: the player on a playing station (no sound played unasked), Save writing to the account, a song opening its record.

### 2026-11-09 – Catch-up with the iPhone: Your computer

- **Your computer page** (iPhone MacDeviceView, e080d4c0, 88355575): Settings > YOUR COMPUTER > the computer's row. Three of the newest records fanned out (the newest in the middle, lifted), a laptop or desktop mark with "YOUR COMPUTER", its name, its state in one word (never a guessed "in sync"), "160 ALBUMS · 1,999 SONGS"; a card with how much of it is on this phone ("All 160 albums on this phone" / "N of M", a meter; the cloud's count on both sides, less what is kept off), Syncs (Everything / Selective, read only: it is the account's), Last synced, Resync Library; then NEW IN YOUR LIBRARY *lately*, the computer's newest records that are on this phone, each playing in one tap. `ui/fragment/ComputerPageFragment.java`, `fragment_computer_page.xml`, `ic_lucide_laptop.xml`, `ic_lucide_monitor.xml`.
- `CassetteComputer` now keeps the summary's `recentItems` and matches them to the phone by the cloud's album key (U+001F folded to NUL, as in CassetteGenres), else by name; `syncedAgo()` gives the bare "23 min ago". Never "Mac" as the general word.
- Checked on the founder's Pixel 8.

### 2026-11-09 – Tune-up: the station's keys, its second line, the player's disc and time bar

- **BUG-955**: the notification, lock screen and car offered Tempo's Shuffle key while a station played. `MediaLibrarySessionCallback.layoutFor` gives a station no custom keys (music keeps its shuffle key), and `MediaService` refreshes the layout for the notification and car controllers on every item change. Seen on the Pixel: `custom actions=[]` with KEXP playing.
- **A station's second line**: with no song named, the line under the show is now its host (the cloud's `show.host`), else nothing in the full player (the station's mark already sits above; the mini player and lock screen keep the station, which they have nowhere else).
- **The play/pause disc**: Media3's PlayerControlView drew its own circled play and pause icons inside our cream disc (a dark disc in a cream one). `inner_fragment_player_controller.xml` now gives it `cs_play_glyph` / `cs_pause_glyph` (`app:play_icon`, `app:pause_icon`).
- **The time bar**: the stretch already loaded was drawn transparent, leaving a gap after the dot on a song read from the phone. It now wears the track's own colour (`cs_hairline`). Portrait only; the landscape layout is still untouched.

### 2026-11-09 – Catch-up with the iPhone: the smaller pieces

- **Artist menu** (long-press an artist): Play, Shuffle, Play Next, Add to Queue, all from the artist's songs on this phone (`DownloadDao.getArtistSync`); "Nothing by them on this phone" when there are none. Tempo's heart, instant mix and random pick (all asked the computer) are gone.
- **Search's start page** (iPhone SearchStartView): a row of quiet chips, Shuffle all (up to 500 songs on the phone), Albums, Playlists, Genres, Radio; each of the last four opens Library on that view. `Cassette.Chip` style (36dp, raised fill, ring).
- **Made for you in Library > Playlists** (iPhone PlaylistsVC I-M8): Home's made-for-you playlists with songs on this phone, under your own, in the same row as your playlists, each opening its page; the drawn cover, else its first record's, as Home shows it. `CassetteMixListAdapter` behind a ConcatAdapter.
- **The car**: Shuffle heads every record, artist, playlist and made-for-you list (CarPlay's header); a tapped Shuffle resolves to that list's songs on the phone, shuffled (`CassetteCar.resolve`, read first in `MediaBrowserTree.getItems`). Home's Radio row is in the car, each station named with its picture and playing from its own stream. Checked on the Pixel by building the lists on the device (temporary hook, removed): Radio row present, a station resolves to its stream, Shuffle resolves to the record's 12 songs. Not checked in a car.
- **ON CASSETTE in Search** (iPhone SearchVC CatalogHit, founder 10-31 Q17): a beat after typing stops, `util/CassetteCatalog.kt` asks the site's own search (`GET /api/search?q=&perPage=6`, public) and shows up to 2 artists, 4 albums and 4 songs under the phone's results as ON CASSETTE *not in your library*, leaving out anything the phone already found (by name). Each opens its page on Cassette (an artist's, else the album's). On its own thread, so a slow network never holds up the phone's results. Checked on the Pixel: "beatles" shows "No results on this phone" then The Beatles, Here Comes the Sun, Let It Be, Yesterday.
- **Delete Account** (BUG-884, iPhone c652049f): Settings > Account, under Sign Out in the danger colour, opens the website's settings, where the delete happens; once the account is gone the phone's next call is refused and it says it was signed out. Not run (it would delete the founder's account).
- The made-for-you rows use the same row as your playlists; the car's Radio row names each station with its picture.
- **THEN in the queue** (iPhone KeepPlayingAlbumCell, founder 11-02): Keep playing now holds its pick instead of adding it as the last song starts. The queue ends with THEN *the cloud's words* and one card (the record's cover, name, its artist in the playing record's colour) with Swap for the next record the cloud named (it keeps every one that is on the phone); a tap plays it now. When the queue runs out (`STATE_ENDED`, repeat off, music), `MediaService` takes the pick and plays it. Not seen on the phone (it needs a queue near its end, which means playing).
- **All shelves** (iPhone AllShelvesVC / ShelfVC): Home's ALL SHELVES *today* row (and the reaching row's heading) opens every shelf made today that the phone holds a record of, the one on Home first with ON HOME, three covers and the name in two voices; a shelf opens on the record-grid page (the genre page's, now taking a shelf id) with its name in two voices, its playlist above its records, PLAY and SHUFFLE. `CassetteHome.shelvesNow` resolves the answer's `shelves` in the same pass as the rows. Checked on the Pixel: 8 shelves; Nostalgia *1997 to 2003* with its playlist and 10 records.
- **The computer card** (iPhone ComputerCard, BUG-899): Home leads with it until the music is in (Not set up, Getting ready, Syncing "3 of 12 albums on this phone", Out of reach, No music yet, Nothing chosen), with one button only where the person can act (Get it for your computer, How to add music, How to choose music, to the site). The decision is `CassetteComputerCard.phase`, ported and unit-tested (10 tests). Checked on the Pixel: hidden with the music in; forced once to Not set up in a throwaway build to see it.
- Delete Account's red didn't show: `Preference.setTitle` skips a title with the same words (`TextUtils.equals` ignores spans); the title is cleared first now.
- **The sideways player** (`layout-land/inner_fragment_player_controller_layout.xml`): was Tempo's July layout (five keys, a heart, no room or glow, no ⋮, none of a station's lines). Now generated from the portrait layout, the same parts, ids and styles, with the cover square on the left half (`vertical_guideline` at 46%) and everything else on the right; regenerate it the same way when the portrait one changes. Checked on the Pixel with a throwaway build locked sideways (manifest restored after): the record-lit room, caps title, the artist in the record's colour, ⋮, a whole time bar, the disc and three keys.
- **Signing in with no computer yet** (BUG-876, BUG-899, iPhone c652049f): an account with no computer used to stop at "No player is registered to this Cassette account yet", which shut out anyone who installs the phone first. Now `/api/player/me` with no computer answers a stand-in (`no-computer.invalid`, which never resolves): the phone signs in, lands on Home's "Not set up" card (radio works), the state word reads "not paired" and Settings hides Resync. While that card shows (every 10 s) and whenever the app comes back to the front, `CassetteComputerLogin.connectIfReady` asks the cloud again; once the account's computer has signed in, the phone saves it (the same `save` the sign-in uses), drops the stand-in's row and starts again on it. Unit-tested (`CassetteNoComputerTest`); not run end to end (it needs an account with no computer; the founder's has one, and signing out on their phone is off limits). Checked on the Pixel that an account with a computer is unchanged.
- **Help**: Settings > Help opens `/help/your-android` (the website's Android article, written the same day in the main repo) instead of the help front page. Until that web change is live, the address answers the site's not-found.

### 2026-11-09 – Security track: sign-in sealed to this phone, the computer by its key, only the sign-in this phone asked for

**Type:** Java/Kotlin patch
**Design:** monorepo `docs/CASSETTE_PHONE_SECURITY_DESIGN_2026-11-09.md` (approved by the founder 11-09, with "nothing goes along to a new phone"). Android rows L3, L4 and L6 of the 11-09 parity audit; BUG-938; the phone half of BUG-859.

- **Rule 1, the sign-in stays on this phone** (L3; iPhone SecureSettingsStore, BUG-878). `util/CassetteSecureStore.kt` seals the Cassette sign-in, the computer's music-server password and the login made from it (token, salt) with an AES-256-GCM key held in the Android Keystore (no screen-lock prompt, works while locked), in its own settings file `cassette_secure.xml`; the slot name rides as associated data. Reads are cached in memory. `Preferences.getPassword/getToken/getSalt/getCassetteBearerToken` (and their setters) read and write through it, so every caller is unchanged. The first read moves an older version's plain copy across and removes it only once the sealed one is saved, so nobody is signed out by the update. The saved-server table no longer holds the computer's password (`CassetteComputerLogin.save` writes it empty; `sealSavedPasswords()` empties it at start for phones that signed in before; `ServerDao.forgetPassword`). A manual "+ Add server" row still keeps Tempo's password in that table (the dev door, BUG-888).
- **Nothing goes along to a new phone** (the founder 11-09): `res/xml/data_extraction_rules.xml` excludes every domain from cloud backup and from Google's phone-to-phone copy (from Android 12 `allowBackup="false"` no longer stops the latter). A new phone opens at Sign in and fetches its music from the computer again.
- **Rule 3, only the sign-in this phone asked for** (BUG-938, L6). Sign in opens the pairing page in a Custom Tab (`androidx.browser`) with a fresh one-time code and a fresh X25519 key (`util/CassettePairing.kt`, `util/CassetteSeal.kt`); both wait sealed in the store for 30 minutes and are used once. `PairCallbackActivity` takes what comes back only when its code matches (`CassettePairing.decide`, unit-tested); a sealed answer (`?sealed=`) is opened with this phone's key, the same format as the computer app's (`pairing.rs` open_sealed, cloud `lib/pairing-seal.ts`), cross-checked against a blob the cloud's own `sealToken` made (`CassetteSealTest`). Until the cloud seals for phones, it answers `?token=&state=` and the plain token is taken only with the matching code; once the cloud seals for phones, drop that branch in `decide`. Anything else says "This sign-in didn't start on this phone, so nothing changed" and changes nothing. Tink (`tink-android`) gives X25519 and HKDF on Android 7 and up.
- **Rule 2, the computer by its key** (L4; iPhone CassetteHubTrust, BUG-879). `util/CassetteHubTrust.kt` keeps the key the cloud reports (`lanTlsPin` from `/api/player/me`, now read into `Cassette.PlayerConfig`) with the computer's name, recorded at sign-in, when the computer arrives, and on every account check (`MainActivity.refreshCassetteAccount`); none, or the no-computer stand-in, forgets it, and so does Sign out. Its `callFactory` is the one door to the computer: while the key is known, a request to the computer's name goes out as https on the same port through a client that accepts that key only (SHA-256 of the certificate's public key, as the hub states it; the certificate names no host, so the key is the identity), with redirects off; everything else passes through unchanged. Every path goes through it: the library (`RetrofitClient` callFactory), music and downloads (`DownloadUtil` now uses `media3-datasource-okhttp`), lock-screen and notification art (`CassetteBitmapLoader`), covers in the app (Glide's `okhttp3-integration`, `CustomGlideModule.registerComponents`) and the sync's cover and photo fetches (`CassetteAlbumArt`, `CassetteArtistArt`). App addresses stay http (downloads, cover keys, the saved server), only the wire changes. It never falls back to plain on its own; only the cloud can withdraw a key.
- **Network rules** (`res/xml/network_security_config.xml`): the cloud is https only (`cassette.digital` and below), trusting the phone's built-in authorities only; certificates someone installed on the phone are trusted in debug builds only. Plain http stays allowed elsewhere, for computers the cloud has no key for yet and for radio.
- **Debug builds only**: `cassette_debug_lan_tls_pin` and `cassette_debug_lan_tls_host` in the settings file stand in for the cloud's key until migration 0126 is applied (set with `adb shell run-as`; the music and cover client logs request lines too, like the library client already did). Never read in a release build.
- **Today, for everyone**: migration 0126 is not applied in production (checked 11-09, read only), so the cloud reports no key and every phone, iPhone included, stays on plain http until the founder applies it. Nothing else is needed: each computer sends its key on its next check-in (desktop 0.5.42 has the gateway), each phone picks it up on its next account check.
- **Checked on the founder's Pixel 8** (installed over the top, data kept): before, the plain settings file held the sign-in, the password, its token and salt; after the first open it held none of them and `cassette_secure.xml` held all four, the saved-server table's password was emptied ("now lives only in the sealed store" in the log), and Home and Library loaded signed in. A planted `cassette://auth/callback?token=…&state=…` showed the not-from-this-phone screen, made no cloud call and left the sign-in in place; `?error=cancelled` still returns to the app. With this Mac's computer key set by hand: every library request went to `https://michaels-macbook-pro.local:4533` and was answered, none plain. With a wrong key: all 9 refused in the handshake ("not this account's computer"), none sent plain, the library still shown from the phone. Key removed: back to plain, all answered. Covers from the cloud loaded through the new Glide door. Unit tests: 50 pass (seal cross-check, pairing rules, a local https server with a hub-style certificate: its key accepted and the request moved to https on the same port, any other key refused before anything is sent, no redirects off it).
- **Not checked**: a whole sign-in through the Custom Tab (the sign-in screen only shows after Sign out, which would revoke the founder's real sign-in); a music download or a computer cover over https on the phone (everything was already on it, so neither fired; both use the door the library proved); the phone-to-phone copy rules (no second phone); a release build on a phone.
- **Still open**: casting to a speaker hands it the computer's plain address with the login in it (a speaker can't check a key; registered separately). The cloud sealing for phones and the iPhone sending a code and key are their own pieces (see the design note).
- **Library > Artists as a list** (founder 11-09, for consistency with the iPhone's ArtistsVC): `CassetteArtistListAdapter` + `item_library_artist_row.xml`, rows of a 48dp round photo, the name and "N albums", filed under the album grid's own letter headings with the # to Z index once past 60 artists. Tempo's photo grid (`ArtistCatalogueAdapter`) is no longer shown. Checked on the Pixel: 119 artists, A to Z, the index jumping by letter.
- **BUG-963 (release build)**: the sideways layout's generator also moved `player_media_extension`, inside the nested (hidden) quality strip, onto `vertical_guideline`, which that box can't see; lint's NotSibling stopped `assembleTempoRelease`. It is back on its own box's `parent`, as in portrait. `assembleTempoRelease` with lint: BUILD SUCCESSFUL (APK unsigned; the repo has no release key). Nothing on screen moves: the strip is GONE.
- **BUG-964 (Search pictures)**: ON CASSETTE drew `/api/search`'s `coverArtUrl` as given, which for a record with no grid size is the archive's 500px copy, so the loader went to archive.org. The rows now draw only the answer's `image` (the image ladder, `{rung}` filled for a 48dp square at the screen's density, 240 on a Pixel 8); a hit with none shows the empty square. Needs the website's matching change live (it adds `image`); until then the rows show empty squares, which is why this build waits for it. `CassetteCatalogTest`.
- **BUG-890 (crash-report switch)**: Settings > Send crash reports did nothing while signed out (automatic reports always went); it now governs them signed in or out, as the privacy page's phone section says. Signed in, the account's choice still overwrites it on each sync pass.
- **BUG-886 (sync load)**: the 30-second check asks the cloud for only this phone's open requests (`?open=1&for_device=`) instead of the account's whole history (11,146 on the founder's account); the phone's own filter stays for an older website.

### 2026-11-10 – Low-hanging bugs before launch

**Type:** small Java/Kotlin and resource patches, one commit each, from the Admin board's Android cards.

- **BUG-826 (a station row's picture)**: Tempo's station row (`InternetRadioStationAdapter`) handed the station's stream address to the picture loader as its cover and showed it as the second line, so the loader opened an audio stream as if it were a picture. The row now draws the station's own picture (`image`) or the empty square, and its label as the second line, never the address. The row sits on Tempo's old radio tab (`HomeTabRadioFragment`), which nothing opens since the Radio dial; fixed anyway so it can't come back broken.
- **BUG-529 (High and Efficient were the same file)**: with the account on High or Efficient, every download asked the computer for a plain MP3 (`format=mp3`), so the two tiers fetched the same file at the computer's default rate. `CassetteDownloadTier` now gives each tier its ceiling, the same as the website's device page and the computer's card sync (`usbTierParams`): High asks for `maxBitRate=256`, Efficient for `128`, Lossless still the original. Applies forward only: a song already on the phone plays from the address saved when it was downloaded, so nothing is fetched again. `CassetteDownloadTierTest`.
- **BUG-530 (Tempo in seven languages)**: the German, French, Italian, Korean, Portuguese, Russian and Chinese files still carried Tempo's own lines: the app's name, "Tempo is an open source client for Subsonic" in About, and the old sign-in and Log out words. Every translated line that names Tempo, or whose English has changed since the fork (checked against the fork's first commit), is removed, so those screens fall back to today's English words; 5 to 9 lines per language, the rest of each translation kept. Three unused English transcode lines no longer say Tempo either. "Built on Tempo" in About stays: it is the GPL credit.
- **BUG-581 (every compilation performer was an artist)**: the Library built Artists from each song's own artist and named a record after its first downloaded song's artist, so a soundtrack added a dozen one-song artists and wore one of them. `CassetteOfflineLibrary.groupAlbums` now gives each record the artist most of its songs are by, else "Various Artists" (`CassetteAlbumGroupKey.effectiveAlbumArtist`, the web's rule, already used by Home, the car and This phone), and `groupArtists` lists only artists with a record of their own; "Various Artists" has no artist id and is left out, as on the web. Search shows a record the same way. A performer only on a compilation is still reached through that record and their songs; their artist page (from a song) still lists their songs. Tapping "Various Artists" on a record page does nothing instead of "error retrieving artist". `CassetteOfflineLibraryTest`.
