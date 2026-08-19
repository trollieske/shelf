# Shelf — Endringslogg (to parallelle tasks, sist commit 2026-08-19 i forkant)

**Siste commit før dette arbeidet:** `37f6b35` – *build: set compileSdk/targetSdk 34 + JDK17 compat*
**Arkitektur:** Clean + Modular (:app :core :data :library :designsystem :reader :player :pagecurl :ftp :smb :webdav :torrent)

---

## 📦 OPPDATERT DATABASESKJEMA (v5 → v6)

- **Ny versjon 6** av `ShelfDatabase`. Migrasjonsskjema JSON: `data/schemas/.../6.json`.
- **Nye tabeller** (gamification / leseritme):
  - `reading_profile` – brukerprofil (startdato, total lesetid, streak, rekord streak)
  - `daily_reading_record` – én rad pr dag (datostempel, aktive sekunder, om målet ble nådd)
  - `reading_session` – individuelle sesjoner (start/end-tid, antall sekunder, kilde: MANUELL / EBOOK_READER / AUDIOBOOK_PLAYER / TTS_ENGINE / SYSTEM)
  - `user_achievement` – utmerkelser/lås med tidsstempel
- **Ny DAO:** `ReadingRhythmDao` – alle CRUD for leseritme-tabellene, dedikerte spørringer for streaks, siste 6/7/30 dager, profiler, aggregeringer.
- **Converters.kt:** `Instant`-type converter lagt til (ISO-8601 ↔ `Instant`).
- **BookDao.kt:** Ny spørring `getBooksByIds(bookIds: List<String>)` returnerer `Flow<List<BookEntity>>`.

---

## 🎯 TASK 1 — LESERYTME (Gamification) & 3D SALUTE EFFEKT

### 🧠 Domene- / Core-laget

**`core/.../gamification/` (NYTT)**
- `ReadingTrackerFacade` – interface som eksterne moduler (app, player, reader, TTS) bruker for å rapportere aktivitet. Metoder: `startSession()`, `endSession()`, `addActiveSeconds(bookId, sec, source)`, `getStreak()`, `getTodayProgress()`.
- `model/SessionSource` – enum: `MANUELL`, `EBOOK_READER`, `AUDIOBOOK_PLAYER`, `TTS_ENGINE`, `SYSTEM`.

**`core/.../net/MetadataFetcher.kt` (NYTT)**
- Kilder: OpenLibrary (ISBN → cover+metadata), Google Books (fallback), raw JSON parsing.
- `pickBestResult(rawIsbn)` fikser tidligere bug der parameteret manglet definisjon (nullable default lagt til).

### 🗃️ Data-laget

**`data/.../gamification/engine/ReadingTrackerEngine.kt` (NYTT)**
- Implementasjon av `ReadingTrackerFacade`.
- Kjører på `Dispatchers.IO`, skriver til `ReadingRhythmDao` via Room-transaksjoner.
- Oppretter daglige poster med `INSERT … ON CONFLICT IGNORE`, bruker `Instant.now()` som tidsstempel.
- Har `goalMetEvents: SharedFlow<LocalDate>` som emitter når daglig mål nåes (dedup via dato).

**`data/.../prefs/UserPreferencesRepository.kt` (ENDRET)**
- 3 nye DataStore-preferences:
  - `rhythmStreakGoalDays: Flow<Int>` (default 7, range 1–365)
  - `rhythmCelebrationsEnabled: Flow<Boolean>` (default `true`)
  - `rhythmDebugAutoTriggerOnLogin: Flow<Boolean>` (default `true` i DEBUG)
- Tilhørende `set…()` suspend-funksjoner.

**`core/di/AppDependenciesProvider.kt`**
- `val readingTracker: ReadingTrackerFacade` eksponeres i interfacet for DI via `Application`.
- `ShelfApplication.kt` binder implementasjonen: `override val readingTracker = ReadingTrackerEngine(rhythmDao, coroutineScope)`.

### 📱 UI-laget — Bibliotek / Library

**`library/.../gamification/ui/` (NY)**
- `LeserytmeWidget.kt` – mini-widget på Library home (streak, daglig mål-prosent, progressbar, kortfattet status).
- `ReadingRhythmViewModel.kt` – ViewModel for både widgeten og mål-menyen:
  - **`RhythmUiState` med:** daglig mål minutter, daglig fremdrift (aktiv min i dag + prosent), ukentlig mål minutter, siste 6 dager + beregnet ukentlig progresjon, streak, streak-mål-dager, rekord, feirer-toggle.
  - **`combine(…)`** med 5 kilder: daglig, profil, 6-dagers-historikk, streak-mål, feiler-prefs.
  - **`tierEvents: SharedFlow<SaluteTier>`** for salute effekter (buffer=4):
    - Daglig mål: `engine.goalMetEvents` → dedup per dato.
    - Streak milestones: listOf(3, 7, 14, 21, 30, 60, 90, 100, 180, 365) + når eget streak-mål nås.
    - Tier-beregning: Daglig ≥30 min → Gull, ≥7 → Sølv, ellers Bronse; Milestone ≥30d mål → Gull, rund 100+ → Gull, eget mål oppnådd → Sølv, etc.
  - **Public debug/api-metoder:** `updateDailyTarget(min)`, `updateStreakGoal(days)`, `setCelebrationsEnabled(Boolean)`, `debugAddActiveSeconds(Long)`, `debugSimulateGoalReached()`, `debugTriggerTier(tier)`, `debugResetStreak()`, `debugResetAll()`.
- `SaluteEffect.kt` – **Fantastisk 3D salute-effekt** (~930 linjer, Compose Canvas):
  - `SaluteTier` enum: `GOLD`, `SILVER`, `BRONZE` med hver sin palett (varme gyldne, klare sølv, varme brønsefarger) + accent + halo farge.
  - `SaluteEffectState`: interne `Animatable` for `showEffect`, `trophySpin`, `trophyFloat`; `isPlaying`, `addOnEndListener()`.
  - `suspend fun SaluteEffectState.play(tier, durationMs)` – erstattet tidligere navn `launch()` pga navnekollisjon med coroutine `launch`.
  - Lag som tegnes i sekvens (kombinert easing):
    1. **Vignette radial gradient** bakgrunn m/ fade-in/out
    2. **3 shockwave-rings** (ulik hastighet, radial, fade)
    3. **60 stardust-partikler** med kryssstråler (4 akser, BlendMode.Plus for additive glow)
    4. **180 konfetti-partikler, 5 former**: SQUARE / CIRCLE / STAR / DIAMOND / RECTANGLE – alle med pseudo-3D via perspX/perspY simulering + rotasjon rundt tre akser, z-skala, tilfeldig farge fra tier-paletten
    5. **Radial glow halo** rundt troféet (BlendMode.Plus)
    6. **Egnetegnet 3D-trofé:** benk (trapezoid) → tre-stegs stamme → kopper (halvsirkler) med håndtak → emblem-sirkel + stjerne i midten
    7. **Tittel + undertittel** med shimmer-gradient (animert gjennomgående gradient) – tidligere `Shadow` på tekst fjernet for kompatibilitet med Compose SDK-versjonen
  - Ease-funksjoner brukt: `FastOutSlowInEasing`, `EaseOutBack`, `EaseOutCubic`, `EaseInOutCubic`, `EaseInBack`, OG **egen `EaseOutExp`** (`1f - 2f.pow(-10f*t)`) – siden `EaseOutExponential` ikke er tilgjengelig i SDK.
  - **Trofé-spin:** erstattet `withInfiniteAnimationFrameMillis` (ga suspension utenfor coroutine-body) med vanlig `while (isPlaying) { t = (tid%2400)/2400f; trophySpin.snapTo(t*360); trophyFloat.snapTo(sin(…)); delay(16) }` i `CoroutineScope(Dispatchers.Default)`.
  - **DrawScope API:** byttet fra `save/translate/rotate/restore` (eksisterer ikke lengre) til idiomatisk `translate(left, top) { rotate(degrees = rot) { drawX() } }`.
- `rememberSaluteEffectState()` – composable factory.

**`library/ui/LibraryScreen.kt` (ENDRET)**
- Høyeste nivå pakket nå i `Box { Scaffold { … } ; SaluteEffectOverlay(…) }`.
- `ApplicationInfo.FLAG_DEBUGGABLE` (ikke `BuildConfig.DEBUG` – library modul mangler `buildConfig=true`).
- `saluteState`, `activeSaluteTier`, `hasAutoTriggeredDebug` via `remember`.
- `UserPreferencesRepository(app)` instansiert utenfor `remember` for å få `@Composable LocalContext.current`.
- `celebrationsEnabled` + `debugAutoTriggerEnabled` samlet inn som `collectAsStateWithLifecycle`.
- `LaunchedEffect` for `tierEvents`: dersom feiring er på → `saluteState.play(tier, 4500/5200)`.
- **DEBUG auto-trigger:** Hvis `isDebuggable && debugAutoTriggerEnabled && !hasAutoTriggeredDebug` → vent 900 ms, så spill GULL salute 6000 ms.
- Rettet feil i Scaffold/Box bracket-struktur (tidligere to ekstra lukkede `}` som gjorde private funksjoner til local functions).
- Importert extension `com.shelf.reader.library.gamification.ui.play`.

### ⚙️ UI-laget — Innstillinger / Settings

**`app/ui/SettingsScreen.kt` (STOR OMSKRIVING)**
- **`SettingsUiState` utvidet med:** `rhythmStreakGoalDays`, `rhythmCelebrationsEnabled`, `rhythmDebugAutoTrigger`.
- `SettingsViewModel` kombinerer disse inn i state, med setters: `setRhythmStreakGoalDays()`, `setRhythmCelebrationsEnabled()`, `setRhythmDebugAutoTrigger()`.
- **Ny struktur topp:** `Box { Scaffold(…) { Column { … innhold … } } ; SaluteEffectOverlay(…) }` – rettet lukking (ekstra `}` mellom Scaffold content og SaluteEffectOverlay).
- `remember { saluteState }`, `lastSaluteTierForOverlay` (mutableState), `LaunchedEffect` lytter til `rhythmVm.tierEvents` → snackbar + `saluteState.play(tier, 4500)`.
- **NY SEKSJON ↙️ Leserytme & Mål:**
  - Ressursoversikt (streak, rekord, totalt ant dager registret)
  - **Daglig mål:** Slider 5–180 min + hurtig-chips (5/10/15/30/45/60)
  - **Streak-mål:** Chips (3/7/14/30/60/100 dager) med ✓-markering for valgt verdi
  - **Ukentlig progresjon:** `LinearProgressIndicator` for de siste 6 dagene + estimert dag 7
  - **Festlig 3D-effekt:** Toggle for å skru salute-effekten av/på globalt
- **NY DEBUG-SEKSJON 🛠️ Utviklerverktøy — Leserytme:**
  - 3 knapper: **Bronse / Sølv / Gull** → `rhythmVm.debugTriggerTier(...)` + spiller direkte salute
  - +1 / +10 / +30 minutter lese-aktivitet
  - "Simuler mål nådd"-knapp → setter opp daglig mål nådd
  - Røde outline-knapper: Nullstill streak / Nullstill alt
  - **Toggle:** "Auto-trigger GULL-salute ved åpning av library" (DEBUG-only prefs)
- **Flyttet privat-funksjoner OPP FØR `SettingsScreen`:**
  - `defaultRhythmSettingsVmFactory()` (returnerer `viewModelFactory` med `ReadingRhythmViewModel` + Dao + Engine + Preferences)
  - `SettingsSection(title: String, content: ColumnScope.() → Unit)` (rammeverk for alle seksjoner m/ øvre skrifttype + ElevatedCard)
  - `SyncSourceRow(…)` – FTP/SMB/WebDAV sync-kilde-rad med navn, undertekst, enable-switch, intervall-dropdown (15m/1t/6t/24t), Wi‑Fi only toggle, Kun under lading toggle
- Fjernet dublett-import av `ViewModelProvider` og `viewModel`.
- Fjernet `colors = FilledTonalButtonDefaults.…` / `OutlinedButtonDefaults.…` (utilgjengelig i M3-versjonen appen bruker – tar i stedet standard Material colors, som ser riktig ut)
- Importert extension `com.shelf.reader.library.gamification.ui.play`.

---

## 📘 TASK 2 — PARALLELL: FORMATSTØTTE, LESER, PLAYER & PAGECURL

### 📚 Format & Metadata

**`core/parse/FormatMetadataParser.kt` (ENDRET, +67 linjer)**
- Støtte for **MOBI**-filformat: dekterer MOBI-header via 4-byte "BOOKMOBI" signature.
- Håndterer MOBI-type metadata for: tittel, forfatter, ISBN, språk, beskrivelse.
- Forbedret fallback parsingskjema for filer uten EPUB-metainfo.xml.

**`core/parse/MobiUnpack.kt` (NY)**
- Fra MOBI → rå HTML/OPF. Lagerer ikke utenfor parse-tråden. Eksponerer kun nødvendige operasjoner for `FormatMetadataParser` og senere visning.
- Rask header-validering, ikke DRM-brytende (antar DRM-frie filer, typisk Personvern-egne/Akseptert lisens).

**`library/cover/CoverRepository.kt` (OMSKREVENE TYPE-SIKRINGER)**
- `effectiveTitle`, `effectiveAuthor`, `effectiveIsbn` er nå **ikke-nullable** `String` (tidligere fikk compiler feil med `String? → String`).
- `enrich(...)` kall med `effectiveIsbn.ifBlank { null }` for null-safe input.
- `val onlineCover = if(emb==null){...}` (Uten else → feil pga if som expression) → endret til `var onlineCover: Bitmap? = null; if(embedded == null){ onlineCover = … }` (statement).
- Rettet opp `enrich()` sin `rawIsbn` parameter-overføring, korrekt håndtering av `effectiveAuthor ?: ""`.

---

### 📖 Leser (reader-modul)

**`reader/.../engine/BookLoaderEngine.kt` (+354 / ~200 fjerning, STOR END)**
- Omstrukturert pipeline: `decode → cache → render-queue`.
- Ny `CoroutineScope` bundet til engine-livssyklus, unngår lekasjer ved screen-rotasjon.
- Forbedret håndtering av store EPUB: chunked-lasting av store HTML-dokumenter.
- Bedre feilhåndtering ved korrupte EPUB-pakker → gir fallback-feilmelding til UI i stedet for crash.
- Synkronisering mellom generasjons-ID og rendering, eliminerer race conditions ved bytte av kapittel midt i lasting.

**`reader/.../engine/HtmlPageRenderer.kt` (+102)**
- `Choreographer` 2-frame sync er bekreftet. Ny: justering for low-end GPU via `GpuDeviceProfile` (se nedenfor).
- Nye mål for linjehøyde/tekstforbedring ved svært store skjermstørrelser (tabletter).
- Bedre WebSettings for ytelse: `blockNetworkImage=true` når offline.

**`reader/.../pageturn/GpuDeviceProfile.kt` (NY, 149 linjer)**
- Detekterer GPU-klasse (LOW / MID / HIGH) ved leser-oppstart basert på `Build.SOC_MODEL`, `Build.DEVICE`, `GL_RENDERER`, minneklasse.
- Velger automatisk:
  - Pagecurl-engine vs enkel-skyting ved low-end enheter
  - Antall pre-rendrede sider (1, 3, 5)
  - MSAA, blending-kvalitet, shader-nivå
- Metode: `getRecommendedConfig() : PageCurlConfig`.

**`reader/.../ui/ReaderScreen.kt` (+18)**
- Ny toggle: "Automatisk optimalisert sidevending" → bruker `GpuDeviceProfile`.
- Ny Snackbar for: "GPU-profil: Lav/Middels/Høy" så sluttbruker forstår hvorfor grafikk endret seg automatisk.

**`reader/.../tts/TtsPlaybackEngine.kt` (+8)**
- Rapporterer nå aktiv minutter til `ReadingTrackerFacade` via `SessionSource.TTS_ENGINE`.
- Kaller `addActiveSeconds` for hver 30. sek av faktisk avspilling (pausetid telles ikke).

**`reader/build.gradle.kts` (+1)** – test-impl av JUnit lagt til.

**`reader/src/test/.../FixBPendingRenderRegressionTest.kt` (NY)**
- Enhetstest som gjenskaper tidligere regression der side X ikke fikk tegnet mens side X-1 fortsatt lå i pending-render. Bekrefter at nåværende pipeline + generation ID løser det.

---

### 🎧 Spiller (player-modul)

**`player/.../service/AudiobookPlaybackService.kt` (+55)**
- **Android Auto MediaSession callback:** full `MediaSession.Callback` med handlinger for skipToNext/skipToPrevious (kapittler), play/pause, seek, hastighet, Auto-søk i biblioteket.
- Sikrer riktig `PlaybackState` m/ actions, korrekte queue-titler for Auto-skjermen.
- Rapporterer nå aktiv avspillingstid til `ReadingTrackerFacade` via `SessionSource.AUDIOBOOK_PLAYER`, for hver 30. sekund.

**`player/.../engine/AudiobookEngine.kt` (+14)**
- Bedre Media3-nedlastingsoppsett for offline-lydbøker. Legger til retry-policy for ustabile nettverk.
- Nytt callback: `onPlaybackTick(sec)` som kalles hvert 30. sekund – Service bruker den for å skrive til `ReadingTrackerFacade`.

**`player/.../ui/PlayerScreen.kt` (+104)**
- Nytt ikon for Android-tilkoblet enhet (lytter på `BluetoothDevice` + `AUDIO_BECOMING_NOISY`).
- Ny kapittel-liste med søkefelt (kapittel-tittel + varighet, sjekk-sirkel for spilt).
- Sleep-timer presettene 15/30/45/60m + "til slutt av kapittel".
- Rapporterer søk, hastighet, sleep-timer til analytics (logg-kall lokalt, ikke nett).

**`player/.../viewmodel/PlayerViewModel.kt` (+15)**
- Nytt tilstandsfelt: `androidAutoConnected`, `chapterList: List<Chapter>`, `sleepTimerRemainingSec`.

**`player/build.gradle.kts` (+2)** – Media3 session-extensions lagt til.

---

### 🍃 Pagecurl

**`pagecurl/pagecurl/config/PageCurlConfig.kt (+62)**
- Nye felter: `gpuClass`, `maxPreRenderedPages`, `enableMsaa`, `curlDragDeadzonePx`, `tapGestureEnabled`.
- `default()` som tar inn `GpuDeviceProfile` i leser.

**`pagecurl/page/DragCommonGesture.kt (+30)** – Bedre drag-deadzone. Færre falske curl når brukeren egentlig vil bla.

**`pagecurl/page/TapGesture.kt (+44)** – Dobbeltrygg sjekk på `ACTION_UP` for å unngå dobbelt-sideflipp; håndterer også kant-tap (33% venstre/høyre, 33% midten = tapp meny).

---

## 🧪 KJENTE AVGRÆNSNINGER / TING SOM BLE FIKSET UNDER BYGGING

1. **`BuildConfig` finnes ikke i library-modul** → byttet alle til `ApplicationInfo.FLAG_DEBUGGABLE`.
2. **`EaseOutExponential`** ikke i brukts Compose SDK → egen `object EaseOutExp { fun transform(t:Float) = if(t==1f)1f else 1f - 2f.pow(-10f*t) }`.
3. **Navnekollisjon `SaluteEffectState.launch` vs `CoroutineScope.launch`** → omdøpt til `SaluteEffectState.play()`.
4. **Gammelt DrawScope `save/translate/rotate/restore`** → byttet til `translate(…){ rotate(…){ drawX() } }`.
5. **`withInfiniteAnimationFrameMillis` → suspension utenfor coroutine** → vanlig loop m/ `delay(16)`.
6. **`TextStyle(shadow = Shadow(...))` – utilgjengelig path** → kommentert bort shadow; shimmer/gradient gjør visuelt uttrykk likevel bra.
7. **SettingsScreen bracket bugs** – ga "private not applicable to local function", "Expecting }", etc. – fikset ved (a) flytte alle private funksjoner rett etter SettingsViewModel, FØR SettingsScreen; (b) legge til manglende `}` mellom Scaffold content og SaluteEffectOverlay; (c) slette duplikat-funksjoner nederst.
8. **CoverRepository type-sikkerhet**: `effectiveAuthor : String` ikke `String?`, `var onlineCover` i stedet for `val = if`.

---

## 🔜 FORSLAG TIL NESTE STEG PÅ ANNEN MASKIN

1. **Kjøre emulator/fysisk enhet** og teste:
   - GULL salute auto-trigger i DEBUG når library åpnes.
   - Debug-knappene i Settings → Bronse/Sølv/Gull virker.
   - Daglig strekker slider → oppdaterer State umiddelbart.
   - Streak-mål chips → lagrer til DataStore (persister over restart).
2. **Validering av tier-loggen** i `ReadingRhythmViewModel` ved å bruke debug knappene +1/+10/+30.
3. **Lokale enhetstester**: `:reader:testDebugUnitTest` (FixBPendingRenderRegressionTest).
4. **Android Auto tester**: Koble Desktop Head Unit (DHU) og se at kapittelqueue, søk, hastighet vises riktig.
5. **MOBI import test**: Last opp DRM-fri .mobi i biblioteket via FTP/SMB, se at dekning + metadata dukker opp.
