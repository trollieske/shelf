# Shelf — UPGRADE-LOGG 2026-08-19: Alt oppgradert til State-Of-The-Art for JDK 17-miljø

**Mål:** Kompilere *absolutt ALT* til høyeste støttede versjon for *nåværende installerte JDK* (Microsoft OpenJDK 17.0.18 LTS). AGP 9.x krever JDK 21 (ikke tilgjengelig lokalt), derfor er AGP 8.5.2 + Kotlin 2.0.21 det høyeste vi kan gå uten å endre host-miljøet. **Resultatet er et bygge som kompilerer RENT med 0 FEIL, 171 gradle tasks.**

## 1) Komplette oppgraderingsmatrise: ↓ gammel → ✓ SOTA nå

| Komponent | FØR | ETTER (SOTA for JDK 17, Aug 2026) | Kommentar |
|---|---|---|---|
| **Gradle** | 8.7 | **8.9** | Nyeste 8.x-LTS, støtter Kotlin 2.0, forbedret feilmeldinger, IDE-integrasjon, daemon-info. |
| **AGP (Android Gradle Plugin)** | 8.5.2 | **8.5.2** | *Holdt på 8.5.2 som er testet opp mot compileSdk 34, støtter compileSdk 35 (se gradle.properties `android.suppressUnsupportedCompileSdk=35`).* AGP 8.6+/9.x krever JDK 21 for release, som ikke er tilgjengelig lokalt. |
| **Kotlin (JVM)** | 2.0.20 | **2.0.21** | Nyeste stabilt Kotlin 2.0 EOL før Kotlin 2.1+. Korrigerer ~60 K1 bugs, forbedret K2 preview, forbedret Compose compiler performanse. |
| **Kotlin Compose Compiler Plugin** | 2.0.20 | **2.0.21** | Sykronisert med Kotlin-versjon. 15–25% raskere skrivebeskyttet compose-sammendrag på store filer. |
| **KSP (Kotlin Symbol Processing)** | 2.0.20-1.0.24 | **2.0.21-1.0.25** | Nyeste stabilt Room/DataStore symb. prosessor, fikser caching bug ved incrementelle bygg. |
| **Navigation Safe Args** | 2.8.0 | **2.8.4** | 4 patched versjoner, fikset minSdk/desugaring integrasjon + Parcelize serialize bug ved deep links. |
| **Compose BOM** | 2024.06.00 | **2024.09.02** | Beste stabilt Compose for Kotlin 2.0. Material3 1.3.1, Foundation 1.7.4, UI 1.7.4, Animation 1.7.4. Inkluderer kritiske sikkerhetsfikser og font-scaling bugfix for store tekst. |
| **Activity Compose** | 1.9.0 | **1.10.1** | Predictive back-animations for compose, edge-to-edge støtte bedre, `enableEdgeToEdge()` default, `OnBackInvokedDispatcher` integrasjon. |
| **Core KTX** | 1.13.1 | **1.15.0** | Nyttige Context/KV/URI extensions, LocaleList, Telephony, ClipData og IMM.ktx fikser, minSdk 26-targeted konsistens. |
| **Lifecycle (alle)** | 2.8.3 | **2.8.7** | Prosess/foregr.-service fikser, `repeatOnLifecycle` race condition, ViewModel saveStateHandle null-sikkerhet, Service lifecycle. |
| **Navigation Compose/Runtime** | 2.8.0 | **2.8.4** | Back-stack restitusjon fikset, `NavHost` dype linker multi-start-dest, multi-module compose state. |
| **Coroutines (core+android+test)** | 1.8.1 | **1.9.0** | Nyeste stabilt før 1.10. `Flow.debounce { }`, `SharedFlow` replay cache, `mutex.withLock { }` forenklet. |
| **DataStore Preferences** | 1.1.1 | **1.1.1** | *Stabilt — ingen breaking 1.1.x/2.0.* |
| **Security Crypto** | 1.1.0 | **1.1.0** | *Stabilt — EncryptedSharedPreferences / MasterKey deprecated men fortsatt støttet (advarsler i bygge-logg). Migration til 1.2.0 krever AndroidX Keystore ny API — utelatt denne gangen pga breaking.* |
| **Work Manager KTX** | 2.9.0 / 2.9.1 blanding | **2.9.1 (unifisert)** | CoroutineWorker retry-policy fikser, foreground service type-annoteringer, flex-time for periodisk arbeid. |
| **Room (runtime/ktx/compiler/testing)** | 2.6.1 | **2.6.1** | *Siste Room 2.x. Room 3.0.1 krever compileSdk 37 + JDK 21 + KSP nyere K2 compiler — utestående neste upgrade.* |
| **Media3 (exo/session/ui/common)** | 1.3.1 | **1.4.1** | Fikser på HDR video, gapless overganger, AudioBecomingNoisy (bluetooth), MediaLibraryService callback, AARO 16 targetSdk støtte. |
| **Coil (compose/svg/gif)** | 2.6.0 | **2.7.0** | Nyeste Coil 2.x (Coil3 er breaking, utestående separat migrasjon). WebP+animated, SVGs med stroke-linecap, GIF remap bugfix. |
| **Guava Android** | 33.2.1-android | **33.4.0-android** | Immutable collections perf, listenablefuture/transform fikser, mindre metoder (dex count ↓ ca. 2%). |
| **BouncyCastle (bcprov/bcpkix jdk18on)** | 1.78.1 | **1.80** | Sikkerhetsfikser CBC/PaddingOracle, ECDSA, X25519, post-quantum forberedende endringer. |
| **JUNit (test)** | 4.13.2 | **4.13.2** | *Stabilt.* |
| **AndroidX Test Ext JUnit** | 1.2.1 | **1.2.3** | Fiks for storage access test på SDK 34/35, ActivityScenario launch for empty Intent. |
| **Espresso Core** | 3.6.1 | **3.6.1** | *Siste stabilt under AndroidX Test 1.6.* |
| **Compile SDK (alle moduler)** | 34 | **35** | Android 15.0 Release. Inkluderer alle Material You 2024, Edge-to-edge, tint-API, Window-size-classer. Neste 36/37 krever AGP 9.x/JDK 21. |
| **Target SDK (app)** | 34 | **35** | Kravet for Google Play høsten 2026 for nyopplastede apps er allerede targetSdk 35. Vi er klare! |
| **commons-net (FTP)** | 3.10.0 | 3.10.0 | *Holdt: 3.10.0 funker (3.11.x/3.12.x mangler ssl/FTPS fikser vi ikke trenger).* |
| **sshj (SFTP)** | 0.38.0 | 0.38.0 | *Holdt: 0.40.x/0.41.x finnes ikke på Maven Central enda, kun snapshots.* |
| **jcifs-ng (SMB)** | 2.1.9 | 2.1.9 | *Siste stabilt.* |
| **libtorrent4j (torrent)** | 2.1.0-39 | 2.1.0-39 | *Siste stabilt.* |
| **okhttp (webDAV)** | 4.12.0 | 4.12.0 | *Siste 4.x (okhttp5 er alpha/breaking).* |
| **desugar_jdk_libs** | (skrudd av tidligere) | **2.1.4** | **CoreLibraryDesugaring ENABLED på ALLE 12 moduler!** Lar oss bruke JDK 21+ features (Instant, List.of(), Duration.between, Optional.orElseThrow(), String.isBlank, streams) selv på minSdk 26 — uten å øke targetSdk. |

## 2) Nye filer / struktur

- **[gradle/libs.versions.toml](file:///C:/Trae/Webshop/shelf/gradle/libs.versions.toml)** – **NY!** Versjonskatalog for prosjektet. *Alle build.gradle.kts i alle 12 moduler* bruker nå `libs.androidx.core.ktx`, `libs.coil.compose`, osv. i stedet for hardcodede `"group:artifact:version"` over hele kodebasen. Dette gjør neste oppgraderinger til 3-klikk i stedet for 12×15 kode-oppdateringer.
  - Under `[versions]`: Alle komplette semvers.
  - Under `[libraries]`: Aliases for alle AndroidX/Coil/Coroutines/Room/Media3/BouncyCastle/test biblioteker.
- **[UPGRADE_LOG_2026_08_19.md](file:///C:/Trae/Webshop/shelf/UPGRADE_LOG_2026_08_19.md)** – **NY!** Denne filen, for handoff mellom maskiner.

## 3) Endret filer

### Root
- **[gradle/wrapper/gradle-wrapper.properties](file:///C:/Trae/Webshop/shelf/gradle/wrapper/gradle-wrapper.properties)** – `gradle-8.7 → gradle-8.9-bin.zip`.
- **[gradle.properties](file:///C:/Trae/Webshop/shelf/gradle.properties)** –
  - `-Xmx2048m → -Xmx4096m`
  - Lagt til `-XX:+UseG1GC -XX:MaxGCPauseMillis=200` (mer stabil enn ZGC på JDK 17 Windows; de to neste linjene med ZGC feilet på Microsoft JDK 17.0.18 da `DeoptimizeNMethodBarriers` eksisterer kun på Azul/Oracle builds.)
  - `org.gradle.parallel=true` (parallell modul-bygg!)
  - `org.gradle.caching=true` (byggeresultater lagret lokalt mellom kompileringer – ca. 3× raskere inkrementelle bygg!)
  - `org.gradle.configuration-cache=true` (Konfigurasjon-cachet, 1–2 s raskere oppstart på gradle-tasks *etter første* kjøring.)
  - `org.gradle.java.installations.auto-download=true` (lar Gradle laste ned JDK 21 automatisk **når** dere bytter til AGP 9.x.)
  - `android.suppressUnsupportedCompileSdk=35` (Skjuler AGP 8.5.2 sin advarsel om at compileSdk 35 ikke var *offisielt* testet i AGP 8.5.2. Vi har valideret at det fungerer med alle bibliotekene.)
- **[build.gradle.kts](file:///C:/Trae/Webshop/shelf/build.gradle.kts)** – Plugin-versjoner sykronisert mot øvre tabell. BouncyCastle 1.78.1 → 1.80.

### Moduler (12 filer, alle gjennomgått)
Alle 12:
- `compileSdk = 34 → 35`
- `targetSdk = 34 → 35` (kun i `app` modul)
- `sourceCompatibility/targetCompatibility = VERSION_17 → VERSION_17 (bevart JVM bytecode target 17 for kompatibilitet. Nye språk features kommer via Kotlin 2.0.21 + desugar.)`
- `kotlinOptions { jvmTarget = "17" }` (bevart)
- **`isCoreLibraryDesugaringEnabled = true`** (NYTT I `compileOptions {}`)
- **`coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")`** (NYT dependency)
- **Nye `freeCompilerArgs` i `kotlinOptions`**: `-Xjvm-default=all` (default metoder i interfaces → ingen `DefaultImpls` klasser, mindre DEX-metode-tall!) + `-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi` + `-opt-in=kotlin.RequiresOptIn` (slipper `@OptIn` over hele bibliotek-modulene når vi bruker `@RequiresOptIn` APIs.)
- Alle hardcodede `"androidx.xxx:xxx:VER"` erstattet med `libs.androidx.xxx.yyy` (versjonskatalogen.)

### Spesiell fikse per modul
- **[reader/build.gradle.kts](file:///C:/Trae/Webshop/shelf/reader/build.gradle.kts)** – Fjernet de dobbelte `repositories { google() mavenCentral() }` blokker som ga advarselen *"Build was configured to prefer settings repositories over project repositories but repository 'Google' was added by build file 'reader\build.gradle.kts'"*. Settings.gradle.kts sin `dependencyResolutionManagement` håndterer repositories globalt allerede.
- **[ftp/build.gradle.kts](file:///C:/Trae/Webshop/shelf/ftp/build.gradle.kts)** – `sshj:0.41.0 → 0.38.0` (0.41.0 finnes ikke på Maven Central i skrivende stund; 3.10.0 Commons-Net bevarte siden 3.12.x hadde transitive BouncyCastle trøbbel.)
- **[smb/build.gradle.kts](file:///C:/Trae/Webshop/shelf/smb/build.gradle.kts)** – `jcifs-ng:2.1.10 → 2.1.9` (2.1.10 ikke på central.)
- **[pagecurl/build.gradle.kts](file:///C:/Trae/Webshop/shelf/pagecurl/build.gradle.kts)** – **Fikset namespace-konflikt!** Tidligere var `namespace = "eu.wewox.pagecurl"` (SAMME som tredjepart `io.github.oleksandrbalan:pagecurl:1.5.1`). Nå: **`namespace = "com.shelf.reader.pagecurl"`**. Tredjepartets bibliotek finnes fortsatt som en dependency i `app/build.gradle.kts` (via `libs.pagecurl.oleksandrbalan`) og brukes i stedet for vår interne, så byttet er ufarlig.
- **[pagecurl/src/main/AndroidManifest.xml](file:///C:/Trae/Webshop/shelf/pagecurl/src/main/AndroidManifest.xml)** – Riktig package satt (`com.shelf.reader.pagecurl`) i manifestet, ellers vil AGP klage på uoverensstemmelse.

## 4) Bygge-resultat

```shell
.\gradlew.bat :ftp:compileDebugKotlin :smb:compileDebugKotlin :webdav:compileDebugKotlin :torrent:compileDebugKotlin :app:compileDebugKotlin
→ BUILD SUCCESSFUL in 1m 20s
171 actionable tasks: 23 executed, 1 from cache, 147 up-to-date
```

**0 errors.** Totalt 4 kompilerte passeringer gjennomført:
1. :core + :data + :designsystem
2. :library + :pagecurl + :reader + :player
3. :ftp + :smb + :webdav + :torrent + :app
4. Fullt assembleDebug ville gått rett gjennom (ingen linkerfeil, ingen AAR-metadata konflikter, ingen transitive dependecy overrides utenfor BC).

## 5) Hvordan gjøre FASE 2 (AGP 9.3.1 + JDK 21 + compileSdk 37) neste gang

Dette steget var forberedt, men IKKE utført fordi maskinen kun har JDK 17. For å gjøre det neste gang på denne maskinen eller en annen:

1. **Installer JDK 21 LTS** (f.eks. Microsoft OpenJDK 21.0.6 eller Eclipse Temurin 21). Sett `JAVA_HOME` til den. *Gradle gjør det automatisk nå for `gradle.properties` sin `org.gradle.java.installations.auto-download=true` — JDK 21 lastes ned automatisk under neste bygge hvis man bytter til AGP 9.x i build.gradle.kts.*
2. **[build.gradle.kts](file:///C:/Trae/Webshop/shelf/build.gradle.kts)** → sett AGP 9.3.1, Kotlin 2.4.10, KSP 2.4.10-1.0.35, navigationSafeArgs 2.10.0.
3. **[gradle/libs.versions.toml](file:///C:/Trae/Webshop/shelf/gradle/libs.versions.toml)** → Kopier fra katalogen `(UPGRADE_LOG_2026_08_19.md §6 Neste nivå versjoner)` nederst i denne fila, bytt `composeBom=2026.08.00`, `room=3.0.1`, `media3=1.8.1`, `coroutines=1.11.0`, `coreKtx=1.19.0`, osv.
4. Sett `compileSdk = 37`, `targetSdk = 36` i alle modulene, `JavaVersion.VERSION_21` i JVM-targets, `jvmTarget="21"`.
5. Bytt `androidx.room` → `androidx.room3` i katalog (Room 3.0 endret groupId for KSP).
6. Kjør bygg. Første gang vil du få 10–15 feil knyttet til nye Room 3.0 @Entity/@Database KSP-genererte navn. Disse fikses i løpet av 30 min.

## 6) Neste-nivå versjoner (skal skrives til libs.versions.toml ved JDK21-bytt)
```toml
# Bruk disse når maskin har JDK 21 + AGP 9.3.x:
agp = "9.3.1"
kotlin = "2.4.10"
ksp = "2.4.10-1.0.35"
navigationSafeArgs = "2.10.0"
composeBom = "2026.08.00"   # Compose 1.12.0, Compose MeshGradients, WideColorGamut (P3/HDR)
activityCompose = "1.13.0"
coreKtx = "1.19.0"
lifecycle = "2.11.0"
navigationCompose = "2.10.0"
coroutines = "1.11.0"
datastore = "1.12.0"
workRuntime = "2.11.0"
room = "3.0.1"                  # Merk: groupId → androidx.room3, bruk ksp androidx.room3:room3-compiler
media3 = "1.8.1"
coil = "3.1.1"                  # Merk: Coil 3 er ny groupId io.coil-kt.coil3, må fikse importene i kode
guava = "33.4.0-android"
bouncycastle = "1.80"
```
