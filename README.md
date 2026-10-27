# ZZT for Android

An offline Android port of the ZZT game using Adrian Siekierka's Zeta DOS emulator. It runs the original ZZT 3.2 engine and includes the official demonstration world. ZZT's IBM PC CGA 8×8 character set and palette are selected as the default display style; the game canvas keeps nearest-neighbor pixel scaling.

## Play

- Rotate the phone to landscape. The app opens fullscreen with the included demo world.
- Tap **PLAY** at the ZZT title prompt.
- Use the cross-shaped D-pad to move. Hold **FIRE**, then a direction, to shoot that way.
- **TORCH** lights a torch. **LOAD WORLD** opens Android's file picker for another `.ZZT` world.

World imports are copied into the app's private storage. ZZT's save data is kept in WebView local storage and IndexedDB for this app's local origin. No network permission or service is used at runtime.

## Build

Requires Android SDK API 36 and JDK 17. The included Gradle wrapper downloads the pinned Gradle 8.11.1 distribution on its first run.

```powershell
cd .\zzt-android
.\gradlew.bat assembleDebug
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Third-party software and game files

- **Zeta 1.2.0 web build** by Adrian Siekierka, MIT license. Source: [asiekierka/zeta](https://github.com/asiekierka/zeta). The Android app packages the official WebAssembly distribution and preserves its license in `app/src/main/assets/third-party/Zeta-LICENSE.txt`.
- **Reconstruction of ZZT 3.2** by Adrian Siekierka, MIT license, based on a reconstruction of ZZT, copyright 1991 Epic MegaGames, used with permission. Source: [asiekierka/reconstruction-of-zzt](https://github.com/asiekierka/reconstruction-of-zzt). The bundled `ZZT.EXE`, `ZZT.DAT`, and official `DEMO.ZZT` are distributed under that project's license; its notice is in `app/src/main/assets/third-party/Reconstruction-of-ZZT-LICENSE.txt`.
- **UZIP.js** by Photopea, MIT license; its license notice is retained in `app/src/main/assets/www/uzip.min.js`.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the bundled component notices. The Android-specific app code does not currently have a separate license; the MIT notices apply to the third-party components listed there.

The Zeta web runtime is used locally inside an Android WebView so the same emulation core, DOS keyboard behavior, BIOS character graphics, and bundled worlds work offline. The Android layer adds immersive display, touch-to-PC-key input, and Android document-picker import.
