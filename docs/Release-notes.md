# Release notes

## 0.1.0 · 2026-09-22 · Preview

Author: liaojinlong. Project: https://github.com/JinlongLiao/TvFinder

### Added

- Remote-friendly sidebar navigation, four-column file grid, visible focus, and an original TV-and-folder logo.
- Capacity display, browsing, and external-app opening for internal shared storage and USB drives.
- Copy, move, rename, permanent deletion, folder creation, progress, and cancellation requests.
- Light, Dark, and System default themes; Light by default, persistent selection, and folder/clipboard restoration.
- Chinese/English UI, in-app language choice, device information, About, offline release notes, and license notices.
- Icons for packages, Office documents, PDF, text, audio/video, images, archives, code, subtitles, e-books, and disk images.

### File protection

- Existing destinations are not overwritten. Path traversal and copying into a source descendant are rejected.
- Move deletes the source only after copying and content verification; failures explain retained source/destination state.
- Delete defaults to Cancel. Moving focus from the grid to the action toolbar retains the file selection.
- Copy cancellation is checked even after writing the final data block.

### Compatibility and limitations

- Android 8.0 / API 26 minimum; application ID io.github.jnlongliao.tv.finder.
- The preview APK uses debug signing and is not an app-store production release.
- Hisense E5Q hardware, USB drivers, NTFS/exFAT writing, and power-loss behavior require validation.
- It does not automatically become the system default file manager. System-entry takeover, NAS/SMB, recycle bin, media player, and APK installer are not included.
- A file-type icon does not mean the format can be executed or played on Android.
- Concurrent modification of the same files by other apps is unsupported. Deletion and the source-deletion stage of a move cannot be rolled back transactionally.

This project uses Apache-2.0. Third-party components retain their original copyrights and licenses; see the third-party notices.
