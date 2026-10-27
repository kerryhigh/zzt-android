# ZZT for Android

An offline Android port of the ZZT game using Adrian Siekierka's Zeta DOS emulator. It runs the original ZZT 3.2 engine and includes the official demonstration world. ZZT's IBM PC CGA 8×8 character set and palette are selected as the default display style; the game canvas keeps nearest-neighbor pixel scaling.

## Play

- Rotate the phone to landscape. The app opens fullscreen with the included demo world.
- Tap **PLAY** at the ZZT title prompt.
- Use the cross-shaped D-pad to move. Hold **FIRE**, then a direction, to shoot that way.
- **TORCH** lights a torch. Tap **WORLD** to send `W` to ZZT and open its built-in world list.
- Tap the gear beside **WORLD** to choose the folder that contains `.ZZT` files. The folder permission and a private snapshot of its worlds are kept across app restarts. Choose the folder again after moving it or changing its access permissions.

The Android folder picker leaves world selection to ZZT. Files are copied into app-private storage with their original filenames, then given deterministic DOS 8.3 aliases when their names need conversion. The bundled `DEMO.ZZT` remains available alongside imported worlds. Replacing the selected folder replaces its imported-world snapshot; old imported names are pruned from Zeta's persistent filesystem before ZZT starts, while `.SAV` game saves remain in IndexedDB. Read-only bundled and imported files take precedence over persistent copies, so an old copy cannot shadow a refreshed world. The app refreshes the remembered folder on startup and uses the last good snapshot if the folder is missing or temporarily inaccessible.

Filesystem and WebView diagnostics are written to Android logcat under the `ZZTAndroid` tag. For example:

```powershell
adb logcat -s ZZTAndroid
```

The reconstructed ZZT Pascal source calls `IOResult` twice when displaying a DOS file error, which can clear the original code before showing it. The source correction is recorded in [patches/reconstruction-of-zzt-display-ioerror.patch](patches/reconstruction-of-zzt-display-ioerror.patch). The bundled `ZZT.EXE` is the upstream prebuilt executable; this Android build does not compile Turbo Pascal sources, so that source correction is not applied to the executable in the APK. The Android-side logs still report the selected world names, DOS aliases, byte counts, failed reads, missing VFS assets, and Zeta startup errors.

## Build

Requires Android SDK API 36 and JDK 17. The included Gradle wrapper downloads the pinned Gradle 8.11.1 distribution on its first run.

```powershell
cd .\zzt-android
.\gradlew.bat assembleDebug
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

For a non-debuggable build, use `assembleRelease`; Gradle writes the unsigned APK to `app\build\outputs\apk\release\app-release-unsigned.apk`. The ready-to-sideload v1.1.0 APK and its SHA-256 checksum are in `release/`. That binary uses the same local Android debug certificate as the previous sideload build so it can be installed as an update. Configure a private release key before distributing it more widely.

## Third-party software and game files

- **Zeta 1.2.0 web build** by Adrian Siekierka, MIT license. Source: [asiekierka/zeta](https://github.com/asiekierka/zeta). The Android app packages the official WebAssembly distribution and preserves its license in `app/src/main/assets/third-party/Zeta-LICENSE.txt`.
- **Reconstruction of ZZT 3.2** by Adrian Siekierka, MIT license, based on a reconstruction of ZZT, copyright 1991 Epic MegaGames, used with permission. Source: [asiekierka/reconstruction-of-zzt](https://github.com/asiekierka/reconstruction-of-zzt). The bundled `ZZT.EXE`, `ZZT.DAT`, and official `DEMO.ZZT` are distributed under that project's license; its notice is in `app/src/main/assets/third-party/Reconstruction-of-ZZT-LICENSE.txt`.
- **UZIP.js** by Photopea, MIT license; its license notice is retained in `app/src/main/assets/www/uzip.min.js`.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the bundled component notices. The Android-specific app code does not currently have a separate license; the MIT notices apply to the third-party components listed there.

The Zeta web runtime is used locally inside an Android WebView so the same emulation core, DOS keyboard behavior, BIOS character graphics, and bundled worlds work offline. The Android layer adds immersive display, touch-to-PC-key input, and Android folder access. No network permission or service is used at runtime.
