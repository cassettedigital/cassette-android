#!/bin/bash
# Build the Cassette release, signed with the release key: the APK cassette.digital hands out (Setup) and the
# bundle (AAB) uploaded to Google Play. Both come from the same commit with the same version.
#
# The key lives outside this repo and outside iCloud:
#   ~/Library/Application Support/Cassette/android/cassette-release.jks  (alias "cassette")
# Its password lives in this Mac's Keychain (service "cassette-android-release", account "cassette"),
# never in a file or a shell profile. Every update has to be signed with the same key, so keep a copy of
# the .jks and its password somewhere safe: without them, a new build can't install over the old one.
#
# A release is built from a commit: it stops on uncommitted changes, so the version (0.1.<commit count>)
# and the build's GIT_SHA always name real source.
#
#   scripts/build-release.sh   -> ~/.cache/cassette-android/out/app/outputs/apk/tempo/release/Cassette-0.1.N.apk
#                                 ~/.cache/cassette-android/out/app/outputs/bundle/tempoRelease/Cassette-0.1.N.aab
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="$(sed -n 's/^sdk\.dir=//p' local.properties)"
KEYSTORE="${CASSETTE_ANDROID_KEYSTORE:-$HOME/Library/Application Support/Cassette/android/cassette-release.jks}"

[ -f "$KEYSTORE" ] || { echo "build-release: no release key at $KEYSTORE" >&2; exit 1; }
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "build-release: uncommitted changes; a release is built from a commit" >&2
  exit 1
fi

CASSETTE_ANDROID_KEYSTORE_PASSWORD="$(security find-generic-password -s cassette-android-release -a cassette -w)"
export CASSETTE_ANDROID_KEYSTORE="$KEYSTORE" CASSETTE_ANDROID_KEYSTORE_PASSWORD

./gradlew -I outside-icloud.init.gradle --no-daemon assembleTempoRelease bundleTempoRelease

VERSION="0.1.$(git rev-list --count HEAD)"
OUT="$HOME/.cache/cassette-android/out/app/outputs"
APK="$OUT/apk/tempo/release/app-tempo-release.apk"
AAB="$OUT/bundle/tempoRelease/app-tempo-release.aab"
"$SDK/build-tools/35.0.0/apksigner" verify --verbose "$APK" | grep -E '^Verified using v[23]'
# a bundle is jar-signed; Google Play re-signs what it hands out (Play App Signing)
"$JAVA_HOME/bin/jarsigner" -verify "$AAB" | grep -q 'jar verified' || { echo "build-release: the bundle isn't signed" >&2; exit 1; }
cp "$APK" "$OUT/apk/tempo/release/Cassette-$VERSION.apk"
cp "$AAB" "$OUT/bundle/tempoRelease/Cassette-$VERSION.aab"
echo "$OUT/apk/tempo/release/Cassette-$VERSION.apk"
echo "$OUT/bundle/tempoRelease/Cassette-$VERSION.aab"
echo "(built from $(git rev-parse --short=8 HEAD))"
