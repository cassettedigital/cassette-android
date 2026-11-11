# Cassette for Android

The Android app for [Cassette](https://www.cassette.digital), the player for music you own. It carries the
music you choose from your own computer and plays it straight from the phone, with or without signal, on
the lock screen and in Android Auto. It also tunes in to Cassette's dial of real radio stations.

Cassette for Android is a modified version of [Tempo](https://github.com/CappielloAntonio/tempo), the
open-source Subsonic client by Antonio Cappiello and its contributors. This fork changes Tempo's sign-in,
library, home, player, radio and look to work with a Cassette account and the Cassette app on your computer.
Thank you to the Tempo project for the foundation.

## Getting the app

Cassette is in beta. Once you're in, open [cassette.digital/setup](https://www.cassette.digital/setup) on your
Android phone and tap Download. Android 7 or later.

## Building it

```
export JAVA_HOME=<a JDK 17>
echo "sdk.dir=<your Android SDK>" > local.properties
./gradlew assembleTempoDebug
```

A release build (`assembleTempoRelease`) comes out unsigned unless the Cassette release key is handed in, so
builds you make yourself install as a separate, unsigned app.

## License

GPL v3, the same as Tempo: see [LICENSE](LICENSE). The bundled fonts Barlow Condensed, DM Mono and
Newsreader are under the SIL Open Font License, with their licences in `app/src/main/assets/licenses/`.
