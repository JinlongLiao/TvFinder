# TV Finder

[简体中文](README.md) | [Native build guide](docs/Native-build-guide.md) | [Release notes](docs/Release-notes.md) | [Third-party notices](docs/Third-party-notices.md) | [Apache-2.0](LICENSE)

![TV Finder logo](交付/应用标志.svg)

A local file manager designed for the current Hisense TV, which reports `VIDAA_TV` as its system model and `MT9653` as its device code. The owner identified its product series as E5Q; the exact retail model has not been confirmed from the TV's system information. The app features a clear sidebar, a four-column file grid, readable typography, storage cards, and visible directional focus.

- **Author:** liaojinlong
- **Project:** [JinlongLiao/TvFinder](https://github.com/JinlongLiao/TvFinder)
- **SSH URL:** `git@github.com:JinlongLiao/TvFinder.git`
- **Application ID:** `io.github.jnlongliao.tv.finder`
- **Version:** 0.1.0, personal-use preview. Some USB behavior has been tested on this TV; full feature acceptance is pending.
- **License:** Apache-2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE), and the separate third-party notices.

## Features

- Internal shared storage and system-exposed USB drives, with total/free/used capacity.
- Direct USB Host access to FAT/exFAT drives that the TV firmware cannot mount. The app can browse and manage files after the Android USB-device permission prompt.
- Browse, open, copy, move, rename, permanently delete files, and create folders.
- Chinese and English UI, including application-generated error messages. Follow the system or choose a language in **About → Language**. Switching language preserves the current folder and pending clipboard item and is unavailable during file operations. Unsupported languages fall back to English.
- Type-specific icons for packages, Word, spreadsheets, presentations, PDF, text, audio, video, images, archives, code, subtitles, e-books, and disk images.
- No ads or NAS/SMB support. HTML preview can load external resources and the app requests network permission.

Extensions are matched without case sensitivity. Recognizing EXE, RPM, or other packages does **not** mean Android can run those programs. Built-in preview depends on the TV decoder for audio and video; other formats can be passed to a compatible installed app. File names are never translated or changed by language selection.

## Appearance

Open **Appearance → Theme** for **Light, Dark, or System default**. Light is the initial default; your selection persists across restarts. Colors apply to storage, files, buttons, remote focus, text fields, and dialogs. Switching preserves the current folder and pending clipboard item. System mode uses the TV night-mode setting and falls back to light if no night flag is supplied. Fixed light/dark choices ignore system changes. During file tasks, system theme changes are deferred until completion; on failure, they wait until the error is dismissed.

## Install and navigate

1. Install the locally generated `交付/电视文件管家-0.1.0-release.apk`. This Release build uses a separate signing key; the APK and private key are not committed to Git.
2. Open **TV Finder** from the TV application list and choose **Allow file access**.
3. Open internal storage or a system-mounted USB drive. For an exFAT drive absent from the system list, choose its device name in the sidebar and grant USB-device access.
4. Use direction keys to select, OK to open, and Back to return to the parent folder.
5. Press Menu, hold OK, or choose **Actions** to copy, move, rename, delete, or inspect the selected file.
6. After copying or moving, open the destination folder and choose **Paste**. Confirm the displayed source and destination.

## File safety and limitations

- Requires Android 11 / API 30 or later and uses the system all-files access permission.
- Internal storage means shared user storage. System data and other apps private directories are not generally accessible.
- Reported capacity comes from the mounted filesystem. It is not the advertised flash capacity, and used space is not a promise of reclaimable space.
- USB discovery uses system storage volumes and readable `/storage` mount points. Hidden vendor mounts, missing permission settings, or read-only NTFS/exFAT drivers require device-specific verification.
- The device-named USB entry is a separate, app-only USB Host path. It does not mount the drive for the TV or other apps, and it does not support NTFS. It supports in-volume folders, copy, move, rename, delete, single-file import through TV Finder's internal browser, and export to `Download/TV Finder`. Source builds also preview audio, video, PDF, text, formatted Markdown, and HTML inside the app. USB playback reads on demand; opening in another app first makes a temporary cached copy for compatibility. HTML may run scripts and load external resources; local resources are limited to the file's directory and children. The existing `交付/电视文件管家-0.1.0-release.apk` is not rebuilt by these source changes.
- Direct access currently targets SCSI Bulk-Only devices with 512/4096-byte logical sectors and READ/WRITE(10) addressing. Multi-partition devices, unusual bridges, power loss, and large files need further acceptance testing. Cross-storage folder moves are not implemented.
- The app still opens on the storage home screen. **System root** in the sidebar opens `/`, and large focusable breadcrumbs jump directly to any ancestor. This entry cannot bypass Android, SELinux, or read-only mount restrictions.
- Existing targets are never silently overwritten or merged. Rename rejects blank names, path traversal, separators, and reserved characters.
- Failed or cancelled copies attempt to remove the destination created by that operation. Cleanup failure reports the path. Power loss, process termination, or unplugging a drive may leave partial data.
- Move means **copy all data → verify contents → delete source**. It requires destination space and additional reads. Source deletion cannot be cancelled or rolled back; failure may leave a complete destination and a partially deleted source.
- These operations are not transactions across apps. Do not change the same source or destination from another app, or unplug drives while an operation is running.
- Delete is permanent; there is no recycle bin. Cancel is focused by default. Cancellation cannot restore files already deleted.
- Diagnostic logs contain operation names, paths, and exception stacks, not file contents. Read them through Android logcat.

## Default file manager

Installing or granting storage permission does **not** replace the system file manager. This version does not implement an `ACTION_GET_CONTENT` picker or a `DocumentsProvider`.

Android reserves the `SYSTEM_DOCUMENT_MANAGER` role for system apps selected by the device manufacturer. A normally installed APK cannot request storage permission to become the system document manager. A future `DocumentsProvider` could offer TV Finder files inside a working system picker, but would not replace its UI. The system picker has already failed to launch on this TV, so that integration cannot be assumed to work here. The TV's USB insertion and NAS prompts are also outside this app's control.

References: [Android roles](https://source.android.com/docs/core/permissions/android-roles) (`SYSTEM_DOCUMENT_MANAGER`) and [DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider).

## Current TV system information

Verified through the paired TV's ADB `getprop` on 2026-09-24:

| Property | TV-reported value |
| --- | --- |
| Brand / manufacturer | `Hisense` / `Hisense` |
| System model | `VIDAA_TV` |
| Device code | `MT9653` |
| Android version / API | `11` / `30` |
| Build display ID | `RP1A.200720.011 release-keys` |
| Android security patch level | `2022-09-05` |

These are firmware-reported values; `VIDAA_TV` is not a confirmed retail model. The owner-provided E5Q series name does not establish the screen size, memory capacity, or “U8 system” version. The app does not hardcode storage behavior from the marketing name.

**Device info** displays the real manufacturer, model, Android/API version, ABI, firmware identifier, and storage permission state. The app does not hardcode a system version based on the TV marketing name.

## Build

Use JDK 17, Android SDK 34, Gradle Wrapper 8.9, and Android Gradle Plugin 8.5.2. Gradle pins NDK `21.4.7075529` and CMake `3.22.1`; the FatFs C sources are included in the repository. Set `ANDROID_HOME` or configure `sdk.dir` in local `local.properties`, accept Android SDK licenses, and allow missing components to download.

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux:

```bash
bash ./gradlew :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Both variants use `io.github.jnlongliao.tv.finder`; the temporary diagnostic probe has been removed. Release signing reads only the local, ignored `.signing/release.properties`. A computer without the private key can still build debug, while Release produces an unsigned APK. Debug signatures can differ across computers, so check the package ID and certificate before updating an installed app. See the [native build guide](docs/Native-build-guide.md) for setup, native sources, ABIs, signing, and troubleshooting. Windows has been built locally; macOS/Linux builds have not been run in this session.

UI and error translations use Android string resources. Business operations use enums rather than translated labels. Version metadata comes from `app/build.gradle` through `BuildConfig`. Bundled release notes and notices are copied from repository documentation during the build.

The specified external `com.wlzn.common.util` source tree is unavailable in this workspace. File operations use Android/Java standard APIs without adding an overlapping utility dependency or custom exception hierarchy.

## Device acceptance

On 2026-09-24, an isolated diagnostic build on the paired Android 11 Hisense TV read an exFAT directory and completed create, write/read-back, rename, copy, and delete operations in a test folder, which was cleaned up. The Release APK was installed and launched; first-run storage permission and USB file operations in the Release package still require device acceptance. User files, files over 4 GiB, full drives, unplugging, standby, and cross-platform builds also remain unverified.

## References

- [Android TV app setup](https://developer.android.com/training/tv/get-started/create)
- [TV navigation](https://developer.android.com/training/tv/get-started/navigation)
- [All-files access and restrictions](https://developer.android.com/training/data-storage/manage-all-files)
- [StorageVolume API](https://developer.android.com/reference/android/os/storage/StorageVolume)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Localization](https://developer.android.com/guide/topics/resources/localization)
- [AGP 8.5 compatibility](https://developer.android.com/build/releases/agp-8-5-0-release-notes)
