# Compendium for Android

[← Back to the README](../README.md)

An Android app that does what the Switch app does: download Persona 5 Royal mods (GameBanana, links, files), unpack
them, and build the `PATCH1.CPK` the game reads. It writes into a folder you choose, using the same layout as a Switch
SD card (`atmosphere/contents/01005CA01580E000/romfs/CPK/PATCH1.CPK`), so it works with an emulator whose "SD card"
(`sdmc`) folder is reachable from shared storage, or with a real SD card you plug into the phone.

> **Status: first port, not tested on a device yet.** The shared C++ core passes its tests and the JNI engine was run
> end to end on a PC (install, turn on and off, remove, build). The Kotlin app and the Android native build could not
> be built where this port was written, so the first CI run may need small fixes. Please open an issue with the
> Gradle log if it does.

## Install

Download `Compendium-<version>-android.apk` from the **Android** workflow run's artifacts (Actions tab), copy it to the
phone and open it (allow "install unknown apps" for your file manager). Android 8.0 or newer, 64-bit (arm64).

First launch: allow *All files access*, then pick the folder to use as the SD card. Emulators put theirs in different
places; look for the folder that contains `atmosphere/`, `nand/` or `Nintendo/`, or pick any folder and point the
emulator's mod/SD setting at it. Android 11+ blocks other apps from `Android/data`, so use an emulator setting that
keeps its data in shared storage.

## What's the same, what differs

| | Switch app | Android app |
|---|---|---|
| Browse GameBanana, links, GitHub releases | yes | yes |
| Install from a file | inbox folder | inbox folder, or pick a file (copied to the inbox) |
| zip / 7z / rar, Aemulus, loose files, ready CPKs, Reloaded-II PC mods | yes | yes (same code) |
| Load order, conflicts, turn on/off, remove | yes | yes |
| Build `PATCH1.CPK` with the game's own files kept | reads the game while running in its place | you pick the game's `PATCH1.CPK` (from a dump) in Settings |
| p5rcbt mode | yes | builds the CPK and can install p5rcbt, but only a console runs the plugin |
| Game version checks | reads it from the console | game version is unknown, so files aren't marked "matches your game" |
| QR link server, controller UI | yes | no (touch UI) |

## How it is built

```
android/app/src/main/java/…   Kotlin + Jetpack Compose UI, a ViewModel, and Bridge (HTTP on Android's network stack)
source/android/engine.cpp     the engine: one JSON call (install, library, GameBanana) on top of source/core
source/android/jni_glue.cpp   JNI: Native.call, progress, and an HttpClient that calls Bridge
source/core/                  unchanged core (CPK reader and writer, mod detection, library)
```

`android/app/src/main/cpp/CMakeLists.txt` builds the core plus libarchive and xz (for 7z) from source for each ABI.
Only `arm64-v8a` is built; add others in `android/app/build.gradle.kts` (`abiFilters`).

Build locally with Android Studio (open `android/`), or from a shell with the SDK, NDK 27.2.12479018 and CMake 3.22.1
installed:

```sh
cd android && ./gradlew assembleRelease
```

The workflow [`android.yml`](../.github/workflows/android.yml) does the same on every push that touches the Android or
core code. Set the secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` to sign with your own key; otherwise a throwaway debug key is used.

## Known limits

- A long install should be done with the app in the foreground; there is no background service yet.
- The game's own CPK files are copied through the file picker, so a multi-GB `ALL_USEU.CPK` takes a while; only
  `PATCH1.CPK` is needed to keep the game's own files, the other CPKs only improve where folderless mod files go.
- Not for Google Play: *All files access* is restricted there. Sideload the APK.
