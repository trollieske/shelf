# 📋 Fullverdig Endringslogg (Changelog)

## 🚀 Kritiske Fikser, Minnebesparelser, Privat Torrent-Støtte & Arbeidsoptimeringer (2026-08-09)

### 1. 🔊 Lydbøker: Full Kapittelstøtte for M4B/M4A (Fikset Kun Én Kapittel)
- **Binær ISOBMFF Kapittel-Parsing**:
  - Ny funksjon `parseMp4Chapters()` i `FormatMetadataParser.kt` som leser det innebygde `chpl`-atomet direkte fra ISOBMFF-containeren (`moov → udta → meta → ilst → chpl`).
  - `MediaMetadataRetriever` støtter ikke kapitler i M4B, så vi parser nå boksstrukturen manuelt. Alle kapitler i eksempelvis *Ringenes Herre* (50+ kapitler) importeres nå korrekt med starttidspunkt og varighet.
  - `BookImportRepository.kt` ekspanderer nå lydbokfiler med innebygde kapitler til flere `ChapterInfo`-objekter i databasen.

### 2. 📖 MOBI: Fikset Falsk DRM-feilmelding (Alltid "Kryptert")
- **Presis PalmDOC + EXTH DRM-Deteksjon**:
  - `BookLoaderEngine` sjekker nå nøyaktig PalmDOC-signatur (`BOOKMOBI`) og MOBI-header + EXTH-header før DRM-konklusjon trekkes.
  - Spesifikk sjekk av DRM-relaterte EXTH-poster (type 206–210) i stedet for å anta kryptering basert på ukjente flagg.
  - **Tekstskanning fallback**: Dersom headerne er ukjente, skannes 256 KB av fila for lesbare tekstsekvenser (≥3 løp på ≥60 tegn). Godtar fila som DRM-fri dersom teksten er menneskelesbar, i stedet for å gi falsk positiv.

### 3. 📚 EPUB/TXT: Riktig Antall Sider & Tekstoppbygging
- **Kolonnelayout Tillater Ubegrenset Bredde**:
  - `HtmlPageRenderer.kt` bruker nå `width: max-content` på `html`, `body` og innholdsbeholdere, i stedet for å låse bredden til visningsbredden.
  - Dette løser at EPUB/TXT kun viste 2–3 sider uavhengig av lengden: tekstkolonnene kan nå vokse fritt, og pagineringen beregnes riktig for hele boka.
  - Overflow-x satt til `visible` slik at kolonner ikke kuttet midt i.

### 4. 🌊 Privat Torrent-Støtte: Fikset Nedlasting fra Private Trackere
- **Kryptert Peer-Tilkobling (RC4 Policy)**:
  - `SettingsPack` i `TorrentEngine.kt` satt med `in_enc_policy=1`, `out_enc_policy=1`, `allowed_enc_level=3` (RC4 preferred) – private trackere avviser vanligvis ukrypterte forbindelser.
  - Peer-fingerprint satt til `qB4630` (qBittorrent 4.6.3) for whitelist-kompatibilitet med private trackere.
- **Disable DHT/LSD/PEX for Private Torrents**:
  - Ny `applyPrivateTorrentFlags()` som via refleksjon setter `disable_dht`, `disable_lsd` og `disable_pex` på `TorrentHandle` når `info.private == 1`.
  - Offentlige fallback-trackere (opentrackr/openbt m.fl.) **filtreres bort** fra magneter hvis torrenten er merket privat.
- **Sekvensiell Nedlasting + First/Last Piece for Alle Torrentkilder**:
  - Tidligere var `SEQUENTIAL_DOWNLOAD` og `first_last_piece_priority` kun på magneter. Nå brukes `applySequentialPriorityFlags()` også for `.torrent`-filer via både `AddTorrentAlert` og `MetadataReceivedAlert`.
- **Utvidet Alert-Maske**:
  - `alert_mask` utvidet med `status`, `progress` og `dht`-flagg, inkludert `metadata_received_alert` slik at private-flagg kan settes umiddelbart når metadata for magneter er ferdig lastet.

### 5. 💾 Minnebesparelse: Trygg Fil-Lasting Av Bøker (Ingen OOM)
- **Chunked Streaming I stedet for readBytes()**:
  - `BookLoaderEngine.kt` erstatter `s.readBytes()` (som laster hele filen inn i minnet) med en strømmende løsning: 64 KB kopibuffer, 256 KB `drmScanHead` for DRM-skanning, og 320 MB hard cap for full tekstbuffer.
  - Forhindrer OutOfMemoryError på store lydbøker og veldig lange ebøker. Totale filstørrelser spores i egen variabel, ikke avhengig av bufferstørrelse.
  - FallbackPacket inneholder nå separat `drmScan`, `fullText` og `sizeBytes`.

### 6. 🖼️ Cover: Minnecache, Nedskalering & Brukerinnstilling
- **LRU Minne-Cache på 12 MB**:
  - `CoverRepository.kt` har nå `android.util.LruCache` for bitmap med 12 MB begrensning; eldre bitmaps recycles automatisk ved `entryRemoved()`.
  - Skriver gjennom cache: lookup først, ellers lasting + cache-sett. Unngår gjentatte fil-dekoding ved hurtigscrolling i biblioteket.
- **2-pass Dekodings-Nedskalering**:
  - Ny privat `decodeSampled()` (for både File og InputStream): først `inJustDecodeBounds`, så `inSampleSize` (2-potenser), så `createScaledBitmap` for nøyaktig mål (ca. 800×1200 for online omslag).
  - Unngår at 12–24 MPX originale omslag lastes inn ufiltrert i minnet.
- **Online Cover Bare Når Brukeren Vil Ha Det**:
  - Før `fetchOnlineCover()` kalles sjekkes nå `prefs.onlineCoverLookup.ENABLED`. Hvis brukeren har skrudd av online-oppslag, gjøres ingen nettverkskall – sparer båndbredde og batteri.
  - `downloadBitmap` setter User-Agent `ShelfEbookReader/1.0` og 8s read timeout.

### 7. 🔋 Torrent Nedlastinger: Wi-Fi/Strøm/Batteri-Begrensninger
- **WorkManager Constraints Oppdatert**:
  - `TorrentDownloadWorker.schedule()/runNow()` leser nå brukerpreferanser (`torrentWifiOnly`, `torrentChargingOnly`) og setter `Constraints`:
    - `UNMETERED` (kun Wi-Fi) ved `wifiOnly=true`, ellers `CONNECTED`
    - `RequiresCharging=true` ved `chargingOnly=true`
    - `RequiresBatteryNotLow`, `RequiresStorageNotLow`
  - Bruker `ExistingPeriodicWorkPolicy.UPDATE` (ikke KEEP lengre) slik at nye begrensninger trer i umiddelbar effekt ved endrede preferanser.
- **Runtime-Sjekk Under Nedlasting**:
  - Hvert 5. sekund i `doWork`-løkka sjekkes nå aktivt:
    - `isBatteryOk()` via `BatteryManager` (minimumsprosent fra prefs)
    - `isCharging()` via `BatteryManager.isCharging`
    - `isMetered()` via `ConnectivityManager.isActiveNetworkMetered`
  - Dersom noen betingelse brytes, pauses ALLE aktive nedlastinger via `pauseActiveDownloads()` som setter `paused` i databasen – sparer batteri og mobilnett.

---

## ⚡ Rettelse av Sortering, Cover-Cache & 3D Page Curl Underliggende Side

### 1. 📚 Deterministisk Sortering (Fikset at Bøker Byttet Plass)
- **Matematisk Stabil Sortering**:
  - Lagt til `.thenBy { it.id }` som fast tie-breaker på alle sorteringsvalg i `LibraryViewModel.kt` (*Dato lagt til, Tittel, Forfatter, Fremdrift*).
  - Forhindrer at bøker med samme importeringsstempel bytter plass eller hopper rundt hver gang databasen oppdateres. Sorteringsrekkefølgen er nå **100% fast og stabil**.

### 2. 🖼️ Fjerning av Cover-popping & Bildere-lasting (Compose & Coil Keys)
- **Eksplisitte Compose & Coil Caching Keys**:
  - Lagt til unike `key`-identifikatorer på tvers av `LazyColumn` og `BookStandingCover3D` i `RealisticBookshelfCanvas.kt`.
  - Satt `memoryCacheKey(path)` og `diskCacheKey(path)` i Coil sin `ImageRequest` for både `BookCoverCard` og `RealisticBookshelfCanvas`.
  - Omslagene lagres nå permanent i minne-cachen og re-renderes **uten blinking, popping eller re-loading**.

### 3. 📖 100% Synlig Underliggende Side ved 3D Page Curl (Fikset Blank Side)
- **Synkron Minnesøk & Forhånds-generering**:
  - Lagt til `getSync(page)` i `PageBitmapCache.kt` for umiddelbar Canvas-tilgang til forhånds-rendret side $N+1$.
  - Implementert automatisk bakgrunns-generering av side $N+2$ mens du leser side $N$.
  - Fjernet forhastet nullstilling av bitmap-bufferne under blaing. Når du drar et ark til venstre, ser du nå **side $N+1$ med full tekst og bilder stående 100% krystallklart under arket**!

### 4. 🖼️ EPUB Bilde-visning (Embed Base64 Data URIs)
- **Automatisk Base64 Embedding**:
  - Konverterer alle relative bilde-stier i EPUB HTML (`<img>` og `<image xlink:href="...">`) direkte fra EPUB-arkivet til `data:image/...;base64,...`.
  - Alle figurer, bilder og omslag i ebøker lastes nå **100% lynraskt og offline** uten avhengighet av fiktive HTTP-adresser.
