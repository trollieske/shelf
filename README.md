# 📚 Shelf – Local-First Ebook & Audiobook Platform

**Shelf** is a premium, local-first Android application that combines an elegant 3D e-book reader, a full-featured audiobook player, an embedded torrent download engine, and smart cloud storage sync (**FTP**, **FTPS**, **SFTP**, **SMB**, **WebDAV**).

Built with **Kotlin 2.0+**, **Jetpack Compose (Material 3)**, **Room DB**, **AndroidX Media3 (ExoPlayer)**, **WorkManager**, and **Navigation Compose**.

> **Locale**: Norwegian (Bokmål) default UI locale with English fallback.

---

## 1. Features at a Glance

### 📖 E-Book Reader & 3D Page Curl
* **3D Page Curl Engine**: Apple iBooks-inspired paper cylinder curvature deformation, fold crease specular highlights, dynamic ambient occlusion, and hardware-accelerated 3D mesh rendering (`PageCurlCanvas.kt`).
* **GPU Architecture**: Adreno 830 / Snapdragon 8 Elite hardware profile detection (`GpuDeviceProfile.kt`) with path clipping (`clipPath`) to eliminate quad tearing on high-refresh OLED displays.
* **Publication-Grade Typography**: Dynamic font sizing (12pt–32pt), light/dark/sepia paper themes, adjustable line height, smart quote normalization, and CSS multi-column layout.
* **DOM Range Measurement**: Accurate total page calculation (`range.getClientRects()`) handling HTML, EPUB, and TXT documents.
* **Multi-Format Reader**: EPUB 2/3, PDF, MOBI, AZW, AZW3, FB2, CBZ, CBR, TXT, Markdown, HTML, RTF, DOCX.

### 🎧 Audiobook Player
* **Media3 ExoPlayer Integration**: Dedicated background service (`AudiobookPlaybackService.kt`) with lock-screen media controls and persistent foreground notifications.
* **Multi-Track Auto-Stitching**: Automatically aggregates folder-based MP3/M4A multi-file audiobooks into unified books.
* **Chapter & M4B Support**: Full chapter parsing from embedded `chap`/`chpl` atoms and ID3 `CHAP` tags.
* **Playback Controls**: Variable playback speed (0.5x – 3.0x), custom skip intervals, and haptic sleep timers.

### ⚡ Torrent & Cloud Storage Engine
* **Embedded Torrent Client**: Background download engine (`TorrentEngine.kt`, `TorrentDownloadWorker.kt`) supporting magnet links, peer/seed stats, and auto-importing completed downloads into the library.
* **FTP / FTPS / SFTP Sync**: Browse remote servers, public-key SSH auth, encrypted Keystore credentials (`EncryptedSharedPreferences`), and periodic background folder watching (`FtpSyncWorker.kt`).
* **SMB (Windows Share) Integration**: Native SMB/CIFS connector (`SmbClientEngine.kt`, `SmbSyncWorker.kt`) for local NAS and PC shares.
* **WebDAV Support**: WebDAV server browser and background library synchronization (`WebdavSyncWorker.kt`).

### 🌲 3D Wooden Bookshelf & UI System
* **Procedural Wood Texture**: Generates walnut (light mode) and dark oak (dark mode) wood textures directly in Compose Canvas (`RealisticBookshelfCanvas.kt`).
* **3D Standing Book Covers**: 3D spine depth shading, warm spotlighting, realistic drop shadows, and spring-physics touch interactions.
* **Clean Metadata Engine**: `MetadataCleaner.kt` strips release noise (`_libgen.li`, `_z-lib.org`, `.epub`, `.pdf`), normalizes underscores, and formats author/title data.
* **Online Cover Lookup**: `CoverRepository.kt` fetches high-resolution covers from Open Library (`covers.openlibrary.org`) and Google Books with fallback to typographic cover generation.

---

## 2. Project Architecture & Modules

```
Book/
├── app/                 # Entry point: MainActivity, navigation graphs, onboarding, screens
├── core/                # Shared domain models (FormatEntity, BookTypeEntity), parsers, MetadataCleaner
├── data/                # Room DB (ShelfDatabase), DAOs (BookDao, ShelfDao, ProgressDao), DataStore prefs
├── designsystem/        # Material 3 theme, RealisticBookshelfCanvas, wood grain generators, color tokens
├── library/             # Library dashboard, BookImportRepository, CoverRepository, LibraryViewModel
├── reader/              # Ebook reader: PageCurlCanvas, ReaderScreen, HtmlPageRenderer, BookLoaderEngine
├── player/              # Audiobook player: AudiobookEngine, AudiobookPlaybackService, PlayerScreen
├── torrent/             # Torrent Engine, TorrentDownloadWorker, magnet link handling
├── ftp/                 # FTP / FTPS / SFTP client engine, server store, FtpSyncWorker
├── smb/                 # SMB / Windows share client engine & sync worker
└── webdav/              # WebDAV client engine & sync worker
```

* **Package Name**: `com.shelf.reader`
* **SDK Config**: `minSdk 26` (Android 8.0) | `targetSdk 35` (Android 15)

---

## 3. Supported Ebook & Audio Formats

### Ebooks & Documents
| Format | Parser Engine | Features |
|---|---|---|
| **EPUB 2 / 3** | `EpubRealParser` | OPF/NCX manifest parsing, HTML chapter extraction, cover extraction. |
| **PDF** | `PdfRenderer` | Vector page rendering, page thumbnails. |
| **MOBI / AZW / AZW3** | PalmDOC / MOBI Parser | Record parsing, cover extraction, HTML text extraction. |
| **FB2 / FB2.ZIP** | FictionBook XML Parser | Structural XML parsing, embedded base64 image extraction. |
| **CBZ / CBR** | Comic Archive Importer | Image extraction & sequential page display. |
| **TXT / Markdown** | `BookLoaderEngine` | Paragraph auto-chunking, multi-charset fallback (**UTF-8**, **Windows-1252** for Norwegian `æ`, `ø`, `å`). |
| **ZIP Containers** | `ArchiveImporter` | Intelligent auto-unpacking and recursive book discovery. |

### Audiobooks
| Format | Decoder | Features |
|---|---|---|
| **M4B** | ExoPlayer | Full embedded chapter atom parsing. |
| **MP3 / M4A / AAC** | ExoPlayer | ID3 `CHAP` frame parsing, multi-file track stitching. |
| **FLAC / OGG / OPUS** | ExoPlayer | Lossless & high-efficiency playback. |

---

## 4. How to Build & Run

### Prerequisites
* **Android Studio**: Iguana / Jellyfish / Ladybug (2024.1+) with Kotlin 2.0 plugin.
* **JDK**: Version 17 or higher.
* **Android SDK**: API 35 (Android 15).

### Command Line
```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug          # Build Debug APK
.\gradlew.bat :app:assembleRelease        # Build Release APK
.\gradlew.bat :app:installDebug           # Build and install to connected device via ADB
```

```bash
# macOS / Linux
./gradlew :app:assembleDebug
```

---

## 5. Security, Permissions & Data Privacy

Shelf is **100% local-first**. All data, database entries, and reading progress reside strictly on the user's device (`/data/data/com.shelf.reader/databases/shelf.db`).

* **No Accounts / No Telemetry**: Shelf contains zero tracking, analytics, or third-party ad SDKs.
* **Scoped Storage & SAF**: Uses Android Storage Access Framework (SAF) for picking files/folders and obtaining **persistable URI permissions**.
* **Keystore Encryption**: All FTP/SFTP passwords and private key passphrases are encrypted using `EncryptedSharedPreferences` backed by the hardware **Android Keystore**.

---

## 6. Developer Documentation & Handover

For detailed technical specifications, diagnostic analyses of rendering components, database query rules, and architectural handoff specs, refer to:

* 📄 **[Technical Handover Report (`handover_report.md`)](file:///c:/Users/cKlappy/Documents/trae_projects/Book/handover_report.md)**
* 📜 **[Changelog (`CHANGELOG.md`)](file:///c:/Users/cKlappy/Documents/trae_projects/Book/CHANGELOG.md)**

---
*Built with care. Keep it calm. Keep it tactile. Keep it yours.*

