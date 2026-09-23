# Native Library Build Guide

TV Finder builds from source on Windows, macOS, and Linux. The repository includes FatFs C sources. Gradle invokes the pinned Android NDK and CMake versions and packages the native libraries in the APK. No host C compiler, manual CMake command, prebuilt `.so`, or separate FatFs download is required. The first build needs network access for Gradle, Android dependencies, and SDK components.

## Toolchain and sources

| Item | Version or location |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper / Android Gradle Plugin | 8.9 / 8.5.2 |
| Android platform / Build Tools | API 34 / 34.0.0 |
| Android NDK / CMake | 21.4.7075529 / 3.22.1 |
| Native build script | `app/src/main/cpp/CMakeLists.txt` |
| Supported ABIs | `arm64-v8a`, `armeabi-v7a` |

`app/build.gradle` links CMake through `externalNativeBuild.cmake` and fixes the two ARM ABIs. `app/src/main/cpp/fatfs/` includes FatFs R0.16, upstream patch-1 and patch-2 applied to `ff.c`, project options in `ffconf.h`, and the original `LICENSE.txt`. `usb_fatfs_bridge.c` implements JNI and the FatFs disk interface; `UsbScsiBlockDevice.java` handles Android USB Host/SCSI transfers; `FatFsVolume.java` loads `libtvfinder_fatfs.so`. Upstream: [FatFs](https://elm-chan.org/fsw/ff/) and [patch list](https://elm-chan.org/fsw/ff/patches.html). x86/x86_64 emulator libraries are not built.

## One-time setup and build

Install JDK 17 and Android Studio or the Android SDK Command-line Tools. Point `ANDROID_HOME` to your SDK, or put `sdk.dir` in a local, ignored `local.properties` file at the repository root. Install `platforms;android-34`, `build-tools;34.0.0`, `ndk;21.4.7075529`, and `cmake;3.22.1`, then accept the relevant SDK licenses. Command-line tools must already be under `cmdline-tools/latest/`. With accepted licenses and network access, AGP can install missing NDK/CMake components automatically.

Windows PowerShell:

```powershell
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" 'platforms;android-34' 'build-tools;34.0.0' 'ndk;21.4.7075529' 'cmake;3.22.1'
.\gradlew.bat :app:assembleDebug
```

macOS / Linux:

```bash
export ANDROID_HOME="/path/to/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-34' 'build-tools;34.0.0' 'ndk;21.4.7075529' 'cmake;3.22.1'
bash ./gradlew :app:assembleDebug
```

After setup, only the last Gradle command is needed for routine builds. `--offline` works only when this computer already has all dependencies and SDK components cached. Local SDK paths, `app/build/`, and `.cxx/` outputs are ignored and do not belong in Git.

## Output and checks

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Both variants use `io.github.jnlongliao.tv.finder`; the temporary `.probe` suffix and probe activity have been removed. Release signing is enabled only when the ignored `.signing/release.properties` and its private key are present. Otherwise `assembleRelease` produces an unsigned APK that cannot be installed. Back up the private key and password file securely; restore them on another computer to sign updates with the same identity. Debug signatures differ across computers and may prevent in-place updates.

Local signing properties example (never commit real credentials):

```properties
storeFile=.signing/tv-finder-release.p12
storePassword=<your-private-password>
keyAlias=tvfinder
keyPassword=<your-private-password>
```

Check that the APK contains the two native libraries:

```powershell
& "$env:JAVA_HOME\bin\jar.exe" tf app\build\outputs\apk\debug\app-debug.apk | Select-String 'lib/(arm64-v8a|armeabi-v7a)/libtvfinder_fatfs.so'
```

```bash
"$JAVA_HOME/bin/jar" tf app/build/outputs/apk/debug/app-debug.apk | grep -E 'lib/(arm64-v8a|armeabi-v7a)/libtvfinder_fatfs\.so'
```

Expect one entry per ABI. A successful build verifies compilation and packaging, not USB permission or exFAT behavior on a particular TV.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| SDK location missing | Set `ANDROID_HOME` or local `sdk.dir`. |
| SDK license not accepted | Run `sdkmanager --licenses` from the same SDK. |
| NDK/CMake missing or `source.properties` missing | Install or reinstall the pinned component through SDK Manager. |
| JDK or class-file version error | Check `java -version`, `JAVA_HOME`, and the IDE's Gradle JDK; use JDK 17. |
| Download or TLS error | Check Gradle, Google Maven, Maven Central, and Android SDK connectivity; retry without `--offline`. |
| `UnsatisfiedLinkError` | Check the APK's `.so` entries and device CPU ABI. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Check package ID and signing certificate before changing an installed app. |

Official references: [NDK/CMake installation](https://developer.android.com/studio/projects/install-ndk), [Gradle native build integration](https://developer.android.com/studio/projects/gradle-external-native-builds), and [AGP 8.5 compatibility](https://developer.android.com/build/releases/agp-8-5-0-release-notes).
