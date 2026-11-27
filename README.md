# No-Nonsense Virtual LAN Adapter (NNVLA) — Android

No-Nonsense Virtual LAN Adapter for Android joins rooms hosted by [NN VLA for Windows](https://github.com/No-Nonsense-OfficialRepo/NN-VLA). This repository contains the Android source for the current development build.

## Download a test build

Get the APK from [NN VLA Android Test Builds](https://github.com/No-Nonsense-OfficialRepo/NN-VLA-Android-Test-Build/releases/tag/v0.3.4).

## Implemented source

- Join with a room key or find a Host on the same Wi-Fi network.
- View the room roster and run connection diagnostics.
- Share phone files with room members. The file browser supports image previews, uploads and downloads.
- Build the ZeroTier JNI library for ARM64 and ARMv7 from the included native sources.

## Development build limits

- The test APK is a debug build for Android API 26 and later.
- Wi-Fi discovery depends on the local network carrying discovery traffic between the phone and Host.
- File sharing requires Android storage permission. Enable read/write sharing only in rooms whose members you trust. Use the phone’s Files link to open the share. If Android lets the app use the default HTTP port, the phone’s VLA IP is sufficient.

## Build on Windows

Install JDK 17, Android SDK Platform 37, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1. Set `ANDROID_HOME` to your SDK or configure it in Android Studio. The included wrapper pins Gradle 9.6.0.

From the repository root in PowerShell, run:

```powershell
Set-Location android
.\gradlew.bat :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. On Linux or macOS, run `sh ./gradlew :app:assembleDebug` from `android/`.

## Run from source

Open `android/` in Android Studio and run the `app` configuration on an ARM device. Start a room in NN VLA for Windows, then enter its room key or tap **Auto Join** on the phone. Approve the Android VPN prompt when it appears.

## Source and licences

The Android app is in `android/app`. The JNI build is in `android/zerotier-core`, and its native dependency sources are in `android/third_party`.

See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
