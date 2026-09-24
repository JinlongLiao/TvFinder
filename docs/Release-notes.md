# Release notes

## 0.1.0 · 2026-09-24 · Local preview update (Debug build)

- Files now open in a built-in preview for common images, PDF, UTF-8 text, media supported by the TV's decoders, and limited plain text from modern Office files.
- Up/Down switches supported files in the same folder. Left/Right turns PDF pages or visible document screens and seeks media by 10 seconds. Menu offers compatible installed apps.
- The preview uses the selected appearance theme and shows an audio information panel. Office layout, formulas, and embedded media are not reproduced.
- Direct USB files use bounded temporary copies; adjacent-file copies are cleaned up during switching or on exit. The per-file limit is 1 GiB.
- The main remote paths were checked with test files on the paired Hisense TV and aigo exFAT drive. See the [device validation report](../交付/预览功能实机验证.md). The earlier Release APK has not been rebuilt with this update.

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
- The earlier Release APK does not automatically become the system default file manager. System-entry takeover, NAS/SMB, recycle bin, built-in media player, and APK installer were not included; this Debug update adds built-in media preview.
- A file-type icon does not mean the format can be executed or played on Android.
- Concurrent modification of the same files by other apps is unsupported. Deletion and the source-deletion stage of a move cannot be rolled back transactionally.

This project uses Apache-2.0. Third-party components retain their original copyrights and licenses; see the third-party notices.
