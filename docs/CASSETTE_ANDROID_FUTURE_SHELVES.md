# Pocketed Home shelves

The stock Tempo Home shelves were removed from the layout and from
`HomeTabMusicFragment` (they were never wired into the live Home and were dead
weight - see the parity work, 2026-07-22). Their **queries were kept**: every
one still lives as a method on `viewmodel/HomeViewModel.java`, unused. This note
is the map back, so resurrecting a shelf is "add a layout block + call the
existing query", not "rebuild the data path".

To bring one back: re-add its sector to `fragment_home_tab_music.xml`, add an
adapter field + an `initXView()` in `HomeTabMusicFragment`, call it from
`onViewCreated`, and observe the ViewModel method below.

| Removed shelf | What it showed | ViewModel query (still present) |
|---|---|---|
| Discover | a random shuffle sample, tap to play | `getDiscoverSongSample`, `getRandomShuffleSample`, `refreshDiscoverySongSample` |
| Made for you (Similar) | songs similar to recent listening | `refreshSimilarSongSample` (+ its getter) |
| Artist radio | artist-seeded radio sample | `refreshRadioArtistSample` (+ getter) |
| Best of artist | top tracks for a surfaced artist | `getBestOfArtists`, `refreshBestOfArtist` |
| Top songs | most-played songs, with a last-week/month/year period filter | `getTopSongs` / period filter (was `filter_top_songs_popup_menu`) |
| Starred tracks / albums / artists | the three favourite shelves | `getStarredTracks(Sample)`, `getStarredAlbums`, `getStarredArtists(Sample)`, `refreshStarred*` |
| New releases | newest releases | `getNewReleases` |
| Flashback | a song from this week in a past year | `getYearList` (+ per-year song fetch) |
| Most played albums | albums by play count | `getMostPlayedAlbums`, `refreshMostPlayedAlbums` |
| Shares | the user's shared items | `getShares`, `refreshShares` |

Note the play-count-dependent ones (Top songs, Most played, Starred) rely on
Subsonic/Navidrome play data, which is the same data gap flagged for the iOS
`forgottenAlbums` / Artists shelves in `docs/CASSETTE_ANDROID_IOS_PARITY.md`.
Discover / Similar / Radio are server-side (`getSimilarSongs`, `getStarred2`,
etc.) and would light up as soon as a shelf calls them.

The rearrangement feature (`reorder()`, `HomeRearrangementDialog`, the
Reorganize button) was removed with these; the Home order is fixed now.
