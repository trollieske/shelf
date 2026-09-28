You are a staff Kotlin Multiplatform engineer. Implement the iOS port of https://github.com/trollieske/shelf in the working tree. The repo is the deliverable. Chat output is a short status log only.

TOKEN RULES
- Do not restate this prompt, do not write a design essay, do not paste unchanged files, diffs, or dependency catalogs into chat.
- Do not explore alternative architectures. The decisions below are final.
- Read a file only to edit it or to confirm a symbol you are about to call.
- Search before reading. Never read the torrent module. Never read pagecurl except to confirm Android call sites you must keep compiling.
- If a command fails, fix the cause. Do not narrate more than 5 lines.
- No new features, no visual redesign, no dependency you do not compile against.

GOAL
Turn this Android-only Kotlin/Compose app into a Kotlin Multiplatform app that still builds Android, and adds an iOS app sharing the maximum amount of existing Kotlin and Compose. Work on branch `kmp-ios`. Commit after each phase below. Do not push. Do not open a PR. Do not delete Android behavior.

FACTS, DO NOT REDISCOVER
- Repo: trollieske/shelf. Kotlin 2.0.21, AGP 8.5.2, Jetpack Compose Material 3, Room, Media3/ExoPlayer, WorkManager, Navigation Compose, KSP.
- Modules: app, core, data, designsystem, library, reader, player, podcast, pagecurl, webdav, smb, ftp, torrent.
- Package root: com.shelf.reader.
- pagecurl is a vendored Apache-2.0 fork of oleksandrbalan/pagecurl (eu.wewox.pagecurl) using Compose graphicsLayer curl. It stays Android-only. Preserve the license header. Do not port it, rewrite it, or reimplement a curl mesh on iOS.
- This environment is Linux. You cannot link the final iOS binary here. Android `assembleDebug` must pass. iOS must be a complete Xcode project a Mac can build. Do not pretend an iOS link succeeded on Linux.

NON-GOALS
- No torrent on iOS. Do not add the torrent module to any iOS source set, export, or Xcode target. Leave the Android torrent module untouched.
- No SMB, no FTP, no Android Auto, no CarPlay, no WorkManager on iOS.
- No SwiftUI rewrite, no Flutter, no React Native, no second app repo, no SQLDelight migration.
- No custom iOS page-curl, no SceneKit, no RealityKit, no SpriteKit, no CATransition pageCurl, no port of graphicsLayer/OpenGL curl.

ARCHITECTURE
Keep the Gradle modules. Convert shared modules to KMP with androidTarget and static iOS frameworks (iosArm64, iosSimulatorArm64). Put platform code in androidMain/iosMain. Expect/actual only at OS boundaries.

iOS v1 surface, and nothing else:
- library, shelves, search, covers, metadata, favorites, reading/listening progress
- local import
- EPUB and PDF reading
- M4B/MP3 playback with chapter and saved position
- WebDAV only if the current client can move to Ktor/common Kotlin without a JNI/Android API. If it cannot, exclude it and leave a single unsupported stub. Do not invent a new sync product.

SHARED
Move pure Kotlin models, mappers, progress rules, sorting, search, metadata, and repository interfaces into commonMain. Do not move a file that imports android.*, AndroidX view/service APIs, Media3, WorkManager, DocumentFile, or the pagecurl package into commonMain.

DATA
Keep Room. Upgrade only as far as official Room KMP requires. Same entities and DAOs. Platform actual only for database builder and file path. Android keeps the current DB file location so existing installs do not reset. iOS DB file lives in the app documents directory. Do not hand-write SQL.

UI
Migrate designsystem, library, and non-reader chrome to Compose Multiplatform in commonMain. Use JetBrains multiplatform Navigation in commonMain. Keep Material 3 look. Coil only if the existing image code already uses Coil and the KMP artifact replaces it without a rewrite. Android app remains the Android shell. Add iosApp as the iOS shell.

READER — HARD SPLIT
Shared: book open, spine, current page/chapter index, progress, TOC, typography settings, pagination inputs.
Android: keep the existing reader and the existing pagecurl module. Do not change curl behavior except to make it compile behind an Android actual.
iOS reading UI is Apple-native, hosted from Compose with UIKitView / UIViewControllerRepresentable:
- Paginated mode uses UIPageViewController(transitionStyle: .pageCurl, navigationOrientation: .horizontal). That transition is the only curl and the only 3D page engine. Phone spineLocation .min. iPad landscape regular width: .mid.
- PDF: PDFKit PDFDocument only. One PDF page view per UIPageViewController page. No custom renderer.
- EPUB: shared pagination produces one page model at a time. Each iOS page is a lightweight UIKit view showing that already-paginated content. Curl, gesture, and 3D turn belong exclusively to UIPageViewController. Do not draw the curl in Compose.
- UIPageViewControllerDelegate writes the settled index back into the shared reader state. Shared code never imports UIKit.
- Scroll mode, if it already exists, may be a plain UIScrollView. It must not use pagecurl.

PLAYER
commonMain gets a small AudioPlayer interface: load, play, pause, seek, chapter, position flow, speed if Android already has it.
androidMain: existing Media3/ExoPlayer and playback service. Do not rewrite it.
iosMain: AVPlayer + AVAudioSession.playback + MPNowPlayingInfoCenter + MPRemoteCommandCenter. No CarPlay. Position ticks update the same progress repository the Android service already uses.

FILES
commonMain: import result as a platform-neutral local file path plus display name.
Android: keep SAF.
iOS: UIDocumentPickerViewController, copy into the app documents library, persist a security-scoped bookmark only if the file is not copied. No content:// URIs in common code.

EXECUTION
1. Create branch kmp-ios. Add a KMP convention plugin or shared Gradle logic. Bump Kotlin, AGP, KSP, Compose Multiplatform, and Room together to current stable versions that officially support this split. Verify versions against official docs if network exists. Do not guess a version and move on after a resolution failure.
2. Make Android compile on the new toolchain before moving source.
3. Extract commonMain from core, then data, then designsystem, then library.
4. Add iosApp, static framework export, and a Swift entry that hosts Compose. Bundle id com.shelf.reader.ios. Display name Shelf.
5. Add the iOS reader host and iOS audio actual.
6. Exclude torrent, smb, ftp, pagecurl, WorkManager, and Media3 from every iOS compilation.
7. ./gradlew :app:assembleDebug must succeed. Fix until it does.
8. Add iosApp/README.md with only the Mac commands: open the project, select the framework scheme, run on a simulator. No architecture essay.
9. Final chat message, max 15 lines: commits, Android build result, iOS files added, anything excluded and why. Stop.
