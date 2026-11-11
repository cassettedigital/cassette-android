# Cassette on Google Play: the internal testing track

The beta goes out through Google Play's **internal testing** track. Testers install from the Play Store, so
they never see Chrome's download block or Play Protect's "harmful app" screen. That screen is what a
sideloaded APK hit on 2026-11-11: it is Tempo's open-source code under a new name and a new signing key.

Internal testing takes up to 100 testers, by their Google account email. Google doesn't review it.
Opening to everyone (production) is a later step. A personal developer account first has to run a
*closed* test with at least 12 testers for 14 days in a row, and internal testing doesn't count toward that.

What's in this repo for it:
- `scripts/build-release.sh` builds the signed bundle `Cassette-0.1.<n>.aab`, beside the APK, from the same commit.
- `fastlane/metadata/android/en-US/` holds the listing text, the 512 px icon and the 1024×500 feature graphic.
- The app targets Android 16 (API 36), which Play has required for new apps since 2026-08-31.

---

## 1. The developer account (founder, once; the slow step)

1. Go to https://play.google.com/console/signup with the Google account that should own Cassette's listing.
2. Choose **Yourself** (a personal account). An organization account needs a D-U-N-S number, which takes
   weeks. Change it later only by opening a new account.
3. Pay the one-time $25. Then verify your identity and contact details. Google also asks you to confirm
   an Android phone through the Play Console app (your Pixel works). **Identity checks take days.**

## 2. Create the app

Play Console > **Create app**:
- App name: `Cassette`
- Default language: English (United States)
- App or game: **App**. Free or paid: **Free**.
- Tick the declarations (Developer Program Policies, US export laws).

## 3. App signing: keep one app across Play and Setup

Play signs what it hands out (Play App Signing). There are two ways to set it up, and the choice is
permanent.

**Recommended: give Play our own key.** Then the app from Play and the APK from Setup are the same
app, and either one updates the other. In **Test and release > App integrity > App signing**, choose to
use your own key: **Export and upload a key from Java keystore**. Console gives you two downloads,
`pepk.jar` and an encryption key file. Put them in `~/Downloads` and ask a session to run the export:

```
java -jar ~/Downloads/pepk.jar --keystore="$HOME/Library/Application Support/Cassette/android/cassette-release.jks" \
  --alias=cassette --output=$HOME/Downloads/cassette-play-key.zip --include-cert --rsa-aes-encryption \
  --encryption-key-path=$HOME/Downloads/encryption_public_key.pem
```

Console shows its own copy of this command, and that one wins if the two differ. It asks for the key's
password, which is in the Keychain ("Cassette Android release key") and in the backup folder. Upload
`cassette-play-key.zip` where Console asks. The same key also serves as the upload key.

**The other way: let Google make a new key.** It's simpler, but anyone who installed the APK from Setup
has to uninstall it before the Play version will install. Uninstalling removes the music on the phone.

## 4. Store listing (Grow users > Store presence > Main store listing)

Copy from `fastlane/metadata/android/en-US/`:
- App name: `title.txt`
- Short description: `short_description.txt`
- Full description: `full_description.txt`
- App icon: `images/icon.png` (512×512)
- Feature graphic: `images/featureGraphic.png` (1024×500)
- Phone screenshots: **at least 2 needed, not made yet.** They have to be signed-in screens: Home, an
  album, the player, the radio dial. Take them on your Pixel, or a session can capture them over USB.
  Covers in them are fine on your own library, but avoid a wall of famous album art.

Store settings: category **Music & Audio**. Contact email `hello@cassette.digital`. Website
`https://www.cassette.digital`.

## 5. App content (Policy and programs > App content)

- **Privacy policy:** `https://www.cassette.digital/legal/privacy`
- **Ads:** No, the app has no ads.
- **App access:** "All or some functionality is restricted." Instructions: "Sign in with a Cassette
  account in the browser page the app opens. With no computer on the account, the radio plays; your own
  music needs the Cassette app on a computer." Add a test account only when Google asks for one; internal
  testing isn't reviewed.
- **Content rating:** run the questionnaire as a music player. No violence, sex, drugs, gambling or
  location sharing. Users don't talk to each other in the app. A shared song is a link with a short note,
  sent through the phone's own share sheet.
- **Target audience:** 18 and over. Picking anything under 18 brings in the Families rules.
- **Advertising ID:** No. The app has no AD_ID permission and no ads or analytics libraries.
- **Government, financial, health, news apps:** none of these apply.

### Data safety

These answers follow the phone section of the privacy page. Change one and you change the other.

- Collects data: **Yes**. Shares data with third parties: **No**. The data goes only to Cassette's own
  servers (service providers don't count as sharing).
- Encrypted in transit: **Yes** (everything is HTTPS).
- Way to request deletion: **Yes**. Deleting your account is on the website: Settings > Account >
  Delete your account. Account deletion URL: `https://www.cassette.digital/dashboard/account`.
- Data types collected. For each: collected, not shared, required for the app to work unless noted,
  purpose **App functionality** (plus **Analytics** for crash and problem reports):
  - **Personal info > User IDs**: the Cassette account the phone is signed in to.
  - **App activity > App interactions**: the songs played, favorites, playlists, saved radio stations,
    followed artists, and the queue and time zone for Keep playing (which can be turned off).
  - **App activity > In-app search history**: what's typed in Search (sent without the account).
  - **App activity > Other user-generated content**: the note added when sharing a song, and the list
    of songs on the phone.
  - **App info and performance > Crash logs** and **Diagnostics**: crash and problem reports, including
    a picture of the screen for a problem report. Optional: they can be turned off.
  - **Device or other IDs**: a random install number sent with crash and problem reports, and the
    phone's make and model for the Devices page.
- Not collected: location, contacts, photos (except the problem-report screenshot), the advertising ID,
  and the music itself (it comes from the person's own computer over their network).

### Foreground services (Policy and programs > App content > Foreground service permissions)

- **Media playback** (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`): plays music with the screen off and on the
  lock screen, and in Android Auto.
- **Data sync** (`FOREGROUND_SERVICE_DATA_SYNC`): copies the albums the person chose from their own
  computer to the phone over Wi-Fi, so they play without signal. It starts when the person sends albums
  to the phone and stops when the copy finishes. If Console asks for a video, record the phone receiving
  an album.

## 6. The first release

1. On this Mac: `apps/cassette-player-android/scripts/build-release.sh`. It prints the `.aab` path.
2. Play Console > **Test and release > Testing > Internal testing > Create new release**.
3. Upload `Cassette-0.1.<n>.aab`. Release notes: `fastlane/metadata/android/en-US/changelogs/default.txt`.
4. **Next > Save and publish** (or Start rollout).

## 7. Testers

1. **Internal testing > Testers**: create an email list ("Cassette beta") and add each tester's Google
   account email. That's the address their phone's Play Store uses, which isn't always the address of
   their Cassette account.
2. Copy the **opt-in link**. A tester opens it on their phone, taps **Become a tester**, then **Download
   it on Google Play**.
3. When the link exists, ask a session to point Setup's Android button at it. Until then Setup keeps
   handing out the APK.

## Every update after

Commit, run `scripts/build-release.sh`, upload the new `.aab` as a new internal testing release, and
publish the same build's APK to Setup with `apps/cassette/scripts/publish-android-download.mts`
(monorepo) so both carry the same version.
