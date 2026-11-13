# Car HUD

Car HUD is a vehicle heads-up display for Android and iPhone. It shows ground speed, altitude above mean sea level, course, coordinates, pitch, and acceleration using the phone's sensors. The portrait layout places an OpenStreetMap below the HUD. In landscape, the map sits on the right. Use the **MAP** switch to turn tile downloads off.

With the vehicle parked and the phone secured in its mount, tap **GO** to calibrate. Red ticks show session maxima, including separate sideways acceleration peaks for left and right. Blue ticks show the lowest altitude and most negative pitch. Tap **ZERO** to reset the session markers and recalibrate.

## Install v1.4

- Android 8.0 or later. Install `CarHUD.apk`.
- iOS 15 or later. Import `CarHUD-iPhone.ipa` into a sideloader such as AltStore Classic, which signs and provisions it for your iPhone.

The GitHub upload archive includes both packages. They can also be attached as assets to a GitHub Release tagged `v1.4`.

## Source layout

| Path | Contents |
| --- | --- |
| `src/app/carhud/`, `AndroidManifest.xml`, `res/` | Native Android app and launcher icon |
| `assets/` | EGM96 elevation grid and source attribution |
| `ios/` | Native iPhone app, bundle metadata, and IPA packaging tools |
| `build.ps1`, `ios/build.ps1` | Windows build scripts for Android and iPhone |

## Build Android on Windows

Install PowerShell 7, JDK 17, Android SDK Platform 35, and Android SDK Build Tools 35.0.0. Run the following from the repository root, substituting your local paths:

```powershell
pwsh -NoProfile -File ./build.ps1 -JavaHome 'C:/path/to/jdk-17' -AndroidJar 'C:/path/to/Android/Sdk/platforms/android-35/android.jar' -BuildTools 'C:/path/to/Android/Sdk/build-tools/35.0.0'
```

The script creates a local test signing key under `.tools/` when no key exists. A newly signed APK requires a fresh installation if the existing app was signed with a different key. Keep signing keys private. The script writes `CarHUD.apk` at the repository root.

## Build the iPhone IPA

The Windows build uses PowerShell 7, Python 3 with Pillow, LLVM 21 with Clang and the Mach-O linker available as `ld64.lld.exe`, and an iPhoneOS SDK usable by Clang on Windows. The v1.4 build used iPhoneOS 16.5 SDK headers and linker stubs. Install Pillow with `python -m pip install Pillow`. Then run:

```powershell
pwsh -NoProfile -File ./ios/build.ps1 -Toolchain 'C:/path/to/llvm' -SDK 'C:/path/to/iPhoneOS.sdk'
```

The script writes `CarHUD-iPhone.ipa` at the repository root. The IPA still needs device provisioning and signing through the sideloader. The iPhone SDK and toolchain are external build dependencies.

The Android release was checked on an emulator. The iPhone release was built and checked for correct bundle and executable metadata. Physical iPhone testing is pending.

Map data © OpenStreetMap contributors. See `assets/EGM96.txt` for the elevation grid's source and public-domain status.
