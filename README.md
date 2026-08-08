# Shelf

**Shelf** is a premium, local-first Android app that combines an elegant ebook reader, a
full-featured audiobook player, and a smart FTP / FTPS / SFTP client. It is built with
**Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, **Media3 (ExoPlayer)**,
**WorkManager**, and **Navigation Compose**.

> Norwegian (Bokmål) is the default UI locale with English as fallback.

---

## 1. Project structure

```
Book/
├── app/                 # Main app module: navigation, screens, DI, Application
├── core/                # Shared domain models & util (BookFormat, ChapterInfo, …)
├── designsystem/        # Material 3 theme, typography, wood textures, shelf components
├── data/                # Room DB, entities, DAOs, repositories (later)
├── library/             # Library UI: bookshelf, grid, list, sample demo books
├── reader/              # Ebook reader module (shell filled in Phase 4)
├── player/              # Audiobook player (Media3 shell filled in Phase 5)
└── ftp/                 # FTP / FTPS / SFTP client + sync module (Phase 6-7)
```

Package name: **`com.shelf.reader`**
- `minSdk 26` (Android 8)
- `targetSdk 35` (Android 15)
- Single-Activity, edge-to-edge, Material 3 with dynamic-colors opt-in (muted by design)
- Light mode = warm paper whites + walnut wood; Dark mode = deep charcoal + dark oak

---

## 2. How to build

### With Android Studio (recommended)
1. Install **Android Studio Hedgehog / Iguana / Jellyfish** (or newer) with the Kotlin 2.0+ plugin.
2. `File → Open → select this repository's folder (Book/)`.
3. Let Gradle sync. If prompted, let it install the correct Gradle wrapper and SDK 35.
4. Connect a device / start an emulator running Android 8.0+ and press **Run ▶**.

### Command-line
Requires **JDK 17+** and the **Android SDK** (platform 35, build-tools 35.x).

```bash
# (Windows PowerShell)
.\gradlew.bat :app:assembleDebug          # debug APK
.\gradlew.bat :app:assembleRelease         # signed release (needs signing config)
.\gradlew.bat :app:lintDebug                # static analysis
.\gradlew.bat test                          # unit tests
```

```bash
# (macOS / Linux)
./gradlew :app:assembleDebug
```

Produced APKs: `app/build/outputs/apk/<variant>/app-<variant>.apk`

### Build variants / splits
`app/build.gradle.kts` enables **ABI splits** (`arm64-v8a`, `armeabi-v7a`, `x86_64`)
plus a **universal** APK. For Play Store use:
```bash
.\gradlew.bat :app:bundleRelease
```

---

## 3. Permissions rationale

| Permission | Why? |
|---|---|
| `INTERNET` + `ACCESS_NETWORK_STATE` + `ACCESS_WIFI_STATE` | FTP/FTPS/SFTP transfers, Smart Sync, optional on-line cover lookup (off by default). **No telemetry / no accounts.** |
| `POST_NOTIFICATIONS` (Android 13+) | Download progress, Smart Sync "new books found" notifications, audiobook playback. Can be turned off in system settings. |
| `FOREGROUND_SERVICE` + `MEDIA_PLAYBACK` + `DATA_SYNC` | Required to reliably play audiobooks and run FTP downloads / Smart Sync while the screen is off. Shelf shows persistent, dismissable foreground notifications while these run. |
| `VIBRATE` | Haptic micro-feedback on presses and sleep-timer completion. |
| `WAKE_LOCK` | Keep Media3 playback running when the CPU would otherwise suspend. |
| `RECEIVE_BOOT_COMPLETED` | (Later phase) Re-enqueue scheduled Smart Sync work after reboot. |

**Storage:** Shelf prefers **Android SAF** (system file & folder pickers) and
**app-specific storage**. It never requests the broad `READ_EXTERNAL_STORAGE`
on Android 13+. For libraries on shared storage the user picks a folder via SAF
and grants **persistable URI permissions** from the system dialog. This is
explained step-by-step in the onboarding flow.

All credentials (FTP/SFTP passwords, SFTP key passphrases) are stored via
`EncryptedSharedPreferences` backed by the **Android Keystore**. Raw passwords
never appear in plaintext in Room tables.

---

## 4. Supported formats

### Ebooks (Reflow + paginated UI in Phase 4)
| Format | Notes |
|---|---|
| EPUB 2 / 3 | Primary. Reflow, chapters, cover, OPF/NCX metadata. |
| PDF | Fast render via `PdfRenderer`; text reflow option (best-effort). |
| MOBI / AZW / AZW3 | Metadata + cover extraction; reflow rendering. |
| FB2 + FB2.ZIP | Full fiction-book support. |
| CBZ / CBR | Comic viewer (image pages). |
| TXT, Markdown, HTML, RTF, DOCX | Best-effort, rendered as styled reflow text. |
| ZIP containers | Unpack intelligently; auto-detect books inside. |

### Audiobooks (Phase 5, Media3 ExoPlayer)
| Format | Notes |
|---|---|
| **M4B (critical)** | Full chapter / bookmark support from embedded `chap` / `chpl` atoms. |
| M4A, MP3, AAC | Standard. Chapters from ID3 `CHAP` frames where present. |
| FLAC, OGG, OPUS, WAV | Lossless / compressed. VBR / CBR. |

**Multi-file audiobooks:** A folder of MP3/M4A is auto-stitched into a single
book. Files are sorted by filename / track number, and each file becomes one
chapter unless better metadata is available.

---

## 5. Adding an FTP / FTPS / SFTP server

1. Tap the **FTP** tab in the bottom navigation.
2. Tap **+ New server** (the pink/purple FAB).
3. Choose a **protocol**:
   - **FTP** – plain unencrypted (rarely used).
   - **FTPS** – TLS over FTP (port 990 default for implicit, 21 for explicit).
   - **SFTP** – SSH File Transfer Protocol (port 22).
4. Fill in **Display name**, **Host**, **Port**, **Username**.
5. Choose one of:
   - **Password** (stored encrypted with Android Keystore)
   - **SSH private key** + optional passphrase (SFTP only)
6. (Optional) Fill **Base path**, toggle **Passive/Active**, **Encoding** (UTF-8).
7. Save and tap the server card to browse its file system.

### Smart Sync rules (Phase 7)
Per server:
- Enable **Smart Sync**.
- Pick one or more remote folders to watch.
- Interval: `Manual`, `On app open`, `Every 15 min`, `Hourly`, `Every 6 h`, `Daily`.
- "Wi-Fi only" toggle on by default.
- Optional filename include/exclude patterns (glob/regex).
- Shelf diffs remote listings by **name + size + mtime**, so repeated runs
  only download truly *new* files.
- A foreground notification lets you review before downloading, or auto-import.

---

## 6. Importing books manually

Four paths, all accessible from the **Import screen** or the library `+` button:

- **System file picker (SAF)** – pick one or many files.
- **"Import folder" (SAF tree)** – scan a whole folder; optional "watch folder"
  that uses a WorkManager periodic task to re-scan.
- **Share intent** – receive files from other apps into Shelf directly.
- **Drag & drop** – drag files onto the Shelf window (on supported launchers
  / tablets / ChromeOS).

Each import runs off the main thread and:
1. Sniffs format & runs the metadata parser.
2. Extracts an embedded cover first; falls back to generating a typographic
   cover instantly (title + author on a tasteful gradient + paper texture).
3. Optionally tries public cover APIs behind the "Online cover lookup" toggle.
4. Inserts into Room and shows the book immediately.

---

## 7. The wooden bookshelf design system

- **Wood textures** are generated procedurally inside Compose Canvas
  (see `WoodGrainGenerator` in [WoodBackground.kt](designsystem/src/main/java/com/shelf/reader/designsystem/components/WoodBackground.kt)).
  This keeps APK size tiny and lets us recolor smoothly between light (walnut)
  and dark (dark oak) mode.
- **Book spines** use spring physics on press, soft ambient occlusion under
  each book, subtle lean angles for variety, and a progress bar near the base.
- The **empty shelf** shows animated dust motes and a single copy line.
- All transitions use Compose animation APIs with spring damping ratios tuned
  to an Apple-like "tactile but calm" feel.
- Typography is an SF-Pro-inspired system using Material 3 text roles and
  generous spacing (see `ShelfTypography`).

---

## 8. Roadmap & Project Status

### Implemented & Production-Ready (Phases 1 – 7 Completed)
- ✅ **Apple-Style 3D Wooden Bookshelf Canvas**: Procedural wood grain lighting, 3D standing book covers with specular highlights, realistic drop shadows, dynamic spotlighting, and center-aligned shelf beams (`RealisticBookshelfCanvas.kt`).
- ✅ **3D Page Curl Ebook Engine**: Ultra-smooth 60 FPS 3D paper cylinder curvature deformation, fold crease specular line rendering, ambient occlusion, and gesture state machine (`PageCurlCanvas.kt`).
- ✅ **GPU Engine & Adreno 830 Optimization**: Automatic Qualcomm Snapdragon 8 Elite / Adreno 830 GPU detection (`GpuDeviceProfile.kt` for OnePlus 13 / `sm8750`), dynamic mesh strip quality tiering (`STRIP_COUNT`), and explicit quad trapezoid path clipping (`clipPath`) eliminating GPU quad tearing and Z-sorting bleed.
- ✅ **Onboarding & Personalization Flow**: 3-step Apple welcome screen ("Velkommen til Shelf", "Hva heter du?", DataStore integration for personalized greetings like `"Hei, Karoline! 👋"`).
- ✅ **Metadata Parsing & Cleaning Engine**: `MetadataCleaner.kt` strips release noise (`_libgen.li`, `_z-lib.org`, `.epub`, `.pdf`), normalizes underscores, splits author/title, and formats metadata with typographic hierarchy.
- ✅ **Online Cover Auto-Fetcher**: Integrated Open Library (`covers.openlibrary.org`) and Google Books APIs fallback into `CoverRepository.kt`.
- ✅ **Layout & Inset Architecture**: Clean single-layer inset handling (`contentWindowInsets = WindowInsets(0.dp)`), non-clipping action buttons, line-clamped book descriptions with "Les mer / Vis mindre" toggles, and 8-point grid filter menu chips with 8dp gaps.
- ✅ **Room Persistence & Clean Slate Database**: Full Room DB integration, DAOs, entities, and clean slate database management.

---

## 9. Notes on data & privacy

Shelf is **100% local-first**. Everything in the tables below lives in
`data/data/com.shelf.reader/databases/shelf.db` and (optionally) in the folder
the user chose as library location.

- No accounts, no tracking, no ads, no crash-reporting SDKs are included.
- Cover lookup (future toggle) uses **public, anonymous** metadata APIs.
- You can **Export library (JSON)** and **Backup** DB from Settings.

---

## 10. Developer Handoff & Endringslogg (Full Changelog)

> **Quick Start for Developers Taking Over:**
> - **Package:** `com.shelf.reader.debug`
> - **Main Activity:** `com.shelf.reader.MainActivity`
> - **Build Command:** `.\gradlew.bat :app:assembleDebug`
> - **ADB Install Command:** `adb install -r app\build\outputs\apk\debug\app-universal-debug.apk`

### Key Architecture Modules & File Registry

| Component / Module | File Path | Description & Responsibilities |
|---|---|---|
| **GPU & 3D Page Curl** | [`PageCurlCanvas.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/reader/src/main/java/com/shelf/reader/reader/pageturn/PageCurlCanvas.kt) | 3D paper mesh cylinder arc deformation engine. Includes quad trapezoid path clipping (`clipPath`) to isolate draws and prevent Adreno GPU tearing. |
| **GPU Profile Detector** | [`GpuDeviceProfile.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/reader/src/main/java/com/shelf/reader/reader/pageturn/GpuDeviceProfile.kt) | Hardware profile detector for Qualcomm Snapdragon 8 Elite / Adreno 830 (OnePlus 13 / `sm8750`). Manages dynamic `STRIP_COUNT` (42 / 24 / 16) and GPU diagnostics logging. |
| **3D Bookshelf Canvas** | [`RealisticBookshelfCanvas.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/designsystem/src/main/java/com/shelf/reader/designsystem/components/RealisticBookshelfCanvas.kt) | 3D standing book covers on wooden shelf beams. Features dynamic center-alignment, warm spotlighting, and 3D spine depth shading. |
| **Metadata Cleaner** | [`MetadataCleaner.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/core/src/main/java/com/shelf/reader/core/parse/MetadataCleaner.kt) | Regex cleaning utility that strips release noise (`_libgen.li`, `_z-lib.org`, `.epub`, `.pdf`), normalizes underscores, and formats author/title. |
| **Online Cover Fetcher** | [`CoverRepository.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/library/src/main/java/com/shelf/reader/library/data/CoverRepository.kt) | Online cover lookup engine querying Open Library and Google Books API fallbacks. |
| **User Preferences** | [`UserPreferencesRepository.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/data/src/main/java/com/shelf/reader/data/prefs/UserPreferencesRepository.kt) | Jetpack DataStore wrapper managing `userName`, `hasSeenOnboarding`, and dark mode preferences. |
| **Screens & Onboarding** | [`Screens.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/app/src/main/java/com/shelf/reader/app/Screens.kt) | Contains 3-step Apple-style Onboarding flow, `BookDetailsScreen` with line clamping ("Les mer / Vis mindre"), and screen routes. |
| **Library View** | [`LibraryScreen.kt`](file:///c:/Users/cKlappy/Documents/trae_projects/Book/library/src/main/java/com/shelf/reader/library/ui/LibraryScreen.kt) | Main library screen with personalized header greeting, `contentWindowInsets = WindowInsets(0.dp)` single inset management, non-clipping buttons, and filter menu chips. |

---

Built with care. Keep it calm. Keep it tactile. Keep it yours.

