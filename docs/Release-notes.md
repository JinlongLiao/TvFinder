# Release notes

## In development · 2026-09-24 · Release APK not rebuilt

- Source builds add in-app audio/video playback, page-by-page PDF preview, plain text and formatted Markdown viewing, and HTML preview. Unsupported TV codecs can still be tried in another app.
- Direct USB files are read on demand inside the app. Opening them in another app first creates a temporary cached copy. Unknown formats can use the system app chooser.
- HTML can run scripts and load online resources; local resources stay within the file directory and its children. Playback and large USB files still need TV acceptance testing.

## 0.1.0 · 2026-09-22 · Preview

Author: liaojinlong. Project: https://github.com/JinlongLiao/TvFinder

### Added

- Remote-friendly sidebar navigation, four-column file grid, visible focus, and an original TV-and-folder logo.
- Capacity display, browsing, and external-app opening for internal shared storage and USB drives.
- Copy, move, rename, permanent deletion, folder creation, progress, and cancellation requests.
- Light, Dark, and System default themes; Light by default, persistent selection, and folder/clipboard restoration.
- Chinese/English UI, in-app language choice, device information, About, offline release notes, and license notices.
- Icons for packages, Office documents, PDF, text, audio/video, images, archives, code, subtitles, e-books, and disk images.
- The storage home remains the default; the sidebar can open system root `/`, and large focusable breadcrumbs jump directly to any ancestor directory.

### File protection

- Existing destinations are not overwritten. Path traversal and copying into a source descendant are rejected.
- Move deletes the source only after copying and content verification; failures explain retained source/destination state.
- Delete defaults to Cancel. Moving focus from the grid to the action toolbar retains the file selection.
- Copy cancellation is checked even after writing the final data block.

### Compatibility and limitations

- Android 11 / API 30 minimum; application ID io.github.jnlongliao.tv.finder.
- The current Release APK uses a separate release signing key. Features remain at the 0.1.0 preview stage and have not completed app-store acceptance. Older debug APKs have a different signing certificate.
- The current Hisense TV reports system model `VIDAA_TV`, device code `MT9653`, and Android 11 / API 30. E5Q is the owner-provided series name; the exact retail model is unconfirmed. Full UI, NTFS, power-loss, and large-file scenarios still need acceptance testing.
- It does not automatically become the system default file manager. System-entry takeover, NAS/SMB, recycle bin, media player, and APK installer are not included.
- A file-type icon does not mean the format can be executed or played on Android.
- Concurrent modification of the same files by other apps is unsupported. Deletion and the source-deletion stage of a move cannot be rolled back transactionally.

This project uses Apache-2.0. Third-party components retain their original copyrights and licenses; see the third-party notices.
