# Debug Session: shelf-ebook-audiobook-torrent-crashes

**Session ID**: `shelf-ebook-audiobook-torrent-crashes`
**Created**: 2026-07-31
**Status**: [OPEN]
**Environment**: Windows 11, Android Gradle Plugin 8.5.2, Kotlin 2.0.20, Compose BOM 2024.06, minSdk 26, targetSdk 35, package `com.shelf.reader`

---

## 1. Beskrivelse / Symptoms

3 hovedkræsj som bruker rapporterer, som Gemini i Android Studio hevder å ha fikset – men fortsatt kræsjer:

| # | Bug | Forventet | Faktisk |
|---|---|---|---|
| B1 | EPUB-parsing: Åpne ebok (EPUB/PDF osv.) viser *«Formatet støttes ikke»* / *«DRM/damaged file»* selv for ren, ikke-DRM EPUB | Boken åpnes og viser innhold på ~2s | Kræsj eller generisk feilmelding for ALLE ebøker |
| B2 | Lydbokavspilling: Trykk *«Hør nå»* på lydbok (M4B/MP3) i biblioteket | Spiller åpnes, MediaSession, avspilling begynner, bakgrunn OK | Appen kræsjer umiddelbart (OnePlus 13, Android 15) |
| B3 | Torrent-søk: UTFør søk i Torrent-modulen | Søkeresultater vises uten kræsj | `UnknownFormatConversionException` pga. URL-koding med `%3A` osv. i String.format |

Gemini hevder i sin melding å ha endret:
1.  `TorrentScreen.kt` → erstattet `String.format` med `replace("%s", ...)`
2.  `AudiobookPlaybackService.kt` → ExoPlayer commands flyttet til `Dispatchers.Main`, FGS-start fikset
3.  `BookFormatParsers.kt` → EPUB: leading slash, case-insensitive XML, ZipFile reuse, dyp fallback-søk etter XHTML

**Konklusjon**: Enten er ikke endringene implementert i filene, eller så har de nye endringene introdusert *nye* bugs (NullPointerException, typefeil, lifecycle, coroutine cancellation).

---

## 2. Hypoteser (Falsifiserbare)

| # | Hypotese | Forventet observasjon hvis SANN | Hvor observeres |
|---|---|---|---|
| **H1** | **Komileringsfeil** i minst én av de 3 filene (TorrentScreen / AudiobookPlaybackService / BookFormatParsers) fra Gemini sine endringer | Gradle :module:compileDebugKotlin slår feil med «Unresolved reference», «Type mismatch», «Suspension function can only...» | assembleDebug output |
| **H2** | **`AudiobookPlaybackService`**: Gemini sin `Dispatchers.Main`-bytte er gjort feil: ExoPlayer/PlayerView beskyttelse er wrapet i `viewModelScope.launch(Dispatchers.Main)` som kanselleres når Activity dør, og FGS `startForeground(NOTI_ID, noti)` er kalt uten `Context.startForegroundService()` først → **IllegalStateException** | Stack trace: `IllegalStateException: not allowed to start service Intent ... app is in background` eller `CalledFromWrongThreadException` | Logcat / bugreport |
| **H3** | **`BookFormatParsers.kt`**: ZipFile-reuse gir **ikke-re-entrant parsing**: Hvis to bøker lastes samtidig, eller en Zip ble lukket av en tidligere feilet parsing → `IllegalStateException: ZipFile closed`, ELLER `META-INF/container.xml` parsing er fortsatt følsom for namespace → ingen root-found → "damaged file" returnert selv for gyldig EPUB | Stack trace: `ZipException` / `IllegalStateException: closed` ELLER `parserEngine.detect()` returnerer `UNKNOWN` for EPUB som faktisk er valid | Parsing-stack i loggen |
| **H4** | **`TorrentScreen.kt`**: Erstattning av `String.format("%s", query)` → `url.replace("%s", query)` er gjort **delvis** – det er fortsatt ett eller flere gjenværende `String.format(url, ...)` kall med URLer som inneholder `%3A` → `UnknownFormatConversionException` direkte i søkeklikk | Log: `UnknownFormatConversionException: Conversion = '3'` eller liknende, i TorrentSearchViewModel / TorrentScreen | Søk i Torrent-modul |
| **H5** | **Prosess-døds-sikkerhet / process death**: Bruker går inn i leser → bytter app → process dør → rekreér StateFlow ved restore → null i `bookId` som ikke håndteres i Reader/Player VMer → NullPointerException | Stack trace: `NullPointerException` i `ReaderViewModel.state.collectAsState()` ved restore etter 30min bakgrunn | På enhet etter lang bakgrunnstid |

---

## 3. Instrumenterings-plan

Når Step 2-3 (kompilering + analyse) er ferdig:

- **H1 verificeres via assembleDebug output** (ingen kodeendring)
- **H2-H4**: Instrumenter med enkel `dbgReport(...)` som POSTer til lokal Debug Server (eller fallback til `android.util.Log.wtf`) i følgende kritiske punkter:
  - AudiobookPlaybackService.onCreate / onStartCommand / prepare / play / pause / seekTo
  - BookFormatParsers: EpubEngine.run / detect / parseManifest / parseContainer
  - TorrentSearchViewModel: performSearch + klikk på resultat
- Start lokal Debug Server i denne maskinen (192.168.1.10:7777 var tidligere nevnt av bruker).

---

## 4. Endringslogg (pre-fix / post-fix)

| Tidspunkt | Hendelse | Status |
|---|---|---|
| 2026-07-31 init | Sesjon opprettet. Plan: kompiler → analyse → instrument → fix → verifiser | 🟡 [OPEN] |
| 2026-08-02 | Fikset `TorrentEngine.kt` Sha1Hash-kompileringsfeil via `Sha1Hash.parseHex` | 🟢 [RESOLVED] |
| 2026-08-02 | Fikset `BookFormatParsers.kt` EPUB parsing: case-insensitive entry lookups, attribute-order invariant rootfile regex, fallback exclusion | 🟢 [RESOLVED] |
| 2026-08-02 | Fikset `AudiobookPlaybackService.kt` trådsikkerhet og typefeil i `optString` | 🟢 [RESOLVED] |
| 2026-08-02 | Verifisert full bygging (`:app:assembleDebug -x lint`) med BUILD SUCCESSFUL | 🟢 [RESOLVED] |

---

## 5. Avslutnings-kriterier (ferdig)

1.  `:app:assembleDebug -x lint` → **BUILD SUCCESSFUL** (ingen kompileringsfeil)
2.  GetDiagnostics → 0 errors (ikke warnings)
3.  Kodeanalyse av H2-H5: enten bekreftet + fikset, eller bevist ikke-eksisterende
4.  Post-fix sammenligning av log (pre-fix kræsj → post-fix suksess)

