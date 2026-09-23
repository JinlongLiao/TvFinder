# TV Finder

[简体中文](README.md) | [Release notes](docs/Release-notes.md) | [Third-party notices](docs/Third-party-notices.md) | [Apache-2.0](LICENSE)

![TV Finder logo](交付/应用标志.svg)

A local file manager designed for TV remotes, targeting the Hisense E5Q. It features a clear sidebar, a four-column file grid, readable typography, storage cards, and visible directional focus.

- **Author:** liaojinlong
- **Project:** [JinlongLiao/TvFinder](https://github.com/JinlongLiao/TvFinder)
- **SSH URL:** `git@github.com:JinlongLiao/TvFinder.git`
- **Application ID:** `io.github.jnlongliao.tv.finder`
- **Version:** 0.1.0, personal-use preview. Hisense E5Q hardware validation is still required.
- **License:** Apache-2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE), and the separate third-party notices.

## Features

- Internal shared storage and system-exposed USB drives, with total/free/used capacity.
- Browse, open, copy, move, rename, permanently delete files, and create folders.
- Chinese and English UI, including application-generated error messages. Follow the system or choose a language in **About → Language**. Switching language preserves the current folder and pending clipboard item and is unavailable during file operations. Unsupported languages fall back to English.
- Type-specific icons for packages, Word, spreadsheets, presentations, PDF, text, audio, video, images, archives, code, subtitles, e-books, and disk images.
- No ads, no network permission, and no built-in player or NAS/SMB support.

Extensions are matched without case sensitivity. Recognizing EXE, RPM, or other packages does **not** mean Android can run those programs. Opening a file requires a compatible app already installed on the TV. File names are never translated or changed by language selection.

## Appearance

Open **Appearance → Theme** for **Light, Dark, or System default**. Light is the initial default; your selection persists across restarts. Colors apply to storage, files, buttons, remote focus, text fields, and dialogs. Switching preserves the current folder and pending clipboard item. System mode uses the TV night-mode setting and falls back to light if no night flag is supplied. Fixed light/dark choices ignore system changes. During file tasks, system theme changes are deferred until completion; on failure, they wait until the error is dismissed.

## Install and navigate

1. Install `交付/电视文件管家-0.1.0.apk`. This is a debug-signed preview APK for personal testing.
2. Open **TV Finder** from the TV application list and choose **Allow file access**.
3. Open internal storage or a USB drive. Use **Refresh drives** after connecting a drive.
4. Use direction keys to select, OK to open, and Back to return to the parent folder.
5. Press Menu, hold OK, or choose **Actions** to copy, move, rename, delete, or inspect the selected file.
6. After copying or moving, open the destination folder and choose **Paste**. Confirm the displayed source and destination.

## File safety and limitations

- Requires Android 11 / API 30 or later and uses the system all-files access permission.
- Internal storage means shared user storage. System data and other apps private directories are not generally accessible.
- Reported capacity comes from the mounted filesystem. It is not the advertised flash capacity, and used space is not a promise of reclaimable space.
- USB discovery uses system storage volumes and readable `/storage` mount points. Hidden vendor mounts, missing permission settings, or read-only NTFS/exFAT drivers require device-specific verification.
- The app still opens on the storage home screen. **System root** in the sidebar opens `/`, and large focusable breadcrumbs jump directly to any ancestor. This entry cannot bypass Android, SELinux, or read-only mount restrictions.
- Existing targets are never silently overwritten or merged. Rename rejects blank names, path traversal, separators, and reserved characters.
- Failed or cancelled copies attempt to remove the destination created by that operation. Cleanup failure reports the path. Power loss, process termination, or unplugging a drive may leave partial data.
- Move means **copy all data → verify contents → delete source**. It requires destination space and additional reads. Source deletion cannot be cancelled or rolled back; failure may leave a complete destination and a partially deleted source.
- These operations are not transactions across apps. Do not change the same source or destination from another app, or unplug drives while an operation is running.
- Delete is permanent; there is no recycle bin. Cancel is focused by default. Cancellation cannot restore files already deleted.
- Diagnostic logs contain operation names, paths, and exception stacks, not file contents. Read them through Android logcat.

## Default file manager

Installing or granting storage permission does **not** replace the system file manager. This version does not implement an `ACTION_GET_CONTENT` picker or a `DocumentsProvider`.

Android controls the standard system file picker. Providing files to it does not replace its UI. Whether a Hisense firmware allows changing the USB insertion handler or a default file manager must be tested on that firmware. All-files access is distinct from the platform document-management permission.

## Hisense information

Checked on 2026-09-22: the official 65E5Q page lists 4 GB RAM and 64 GB storage, but its searchable text does not identify the underlying Android version. The relationship between the name “U8 system” and the actual E5Q firmware remains unconfirmed from official documentation.

**Device info** displays the real manufacturer, model, Android/API version, ABI, firmware identifier, and storage permission state. The app does not hardcode a system version based on the TV marketing name.

## Build

Use JDK 17, Android SDK 34, Gradle Wrapper 8.9, and Android Gradle Plugin 8.5.2. Set `ANDROID_HOME` or configure `sdk.dir` in your local `local.properties`.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

UI and error translations use Android string resources. Business operations use enums rather than translated labels. Version metadata comes from `app/build.gradle` through `BuildConfig`. Bundled release notes and notices are copied from repository documentation during the build.

The specified external `com.wlzn.common.util` source tree is unavailable in this workspace. File operations use Android/Java standard APIs without adding an overlapping utility dependency or custom exception hierarchy.

## Device acceptance

Use disposable files first. Check bidirectional internal/USB transfers, rename, delete, remote Menu/long-press behavior, compatible file-opening apps, and storage permission denial/recovery. Large files, full drives, USB removal, and TV standby need separate hardware tests. Emulator results do not prove Hisense driver compatibility.

## References

- [Official Hisense 65E5Q product page](https://mall.hisense.com/items/6096)
- [Android TV app setup](https://developer.android.com/training/tv/get-started/create)
- [TV navigation](https://developer.android.com/training/tv/get-started/navigation)
- [All-files access and restrictions](https://developer.android.com/training/data-storage/manage-all-files)
- [StorageVolume API](https://developer.android.com/reference/android/os/storage/StorageVolume)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Localization](https://developer.android.com/guide/topics/resources/localization)
- [AGP 8.5 compatibility](https://developer.android.com/build/releases/agp-8-5-0-release-notes)
