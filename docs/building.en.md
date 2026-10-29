# Building

[Русский](building.md) · [Home](../README.en.md)

Eve Box uses Java and native Android Views. Install JDK 17, Android SDK
Platform 35 and Build Tools 35.0.0. The repository pins Android Gradle Plugin
8.9.2 and Gradle Wrapper 8.11.1. Minimum Android version is 8.0 (API 26);
target SDK is 35.

Open the project in Android Studio or point `ANDROID_HOME` or a local
`local.properties` at your SDK. Keep machine-specific paths and keys out of Git.

## Debug APK

From the repository root:

```sh
./gradlew :app:assembleDebug
```

On Windows PowerShell, use `./gradlew.bat` instead of `./gradlew`.
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The debug package is `io.github.ekkzy.softlumi.debug`.

## Checks

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew :app:assembleDebugAndroidTest
```

Native UI tests live in `app/src/androidTest`. They need a dedicated test
Android TV device or emulator; some change test preferences and use fixtures.
Read a test's setup before running it on a personal Home screen. Compiling
the test APK does not run its tests.

## Release

```sh
./gradlew :app:assembleRelease
```

Release builds use R8 and resource shrinking. The output is unsigned by
default. Sign it with your own persistent Android key before installation;
future updates require the same key. No private release key is in this repository.

Resource licenses are listed in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Checks on GitHub

GitHub Actions is not configured yet. Run builds, JVM tests and lint locally
with the commands above. Emulator scenarios are available in
`scripts/ci-tv-smoke.sh` and `scripts/ci-tv-permission.sh`.
Hosted checks can be added separately.
