# Shelf Reader — Motorola Edge 50 Pro kompatibilitets-feilsøking
**Dato:** 2026-08-14
**App:** `com.shelf.reader` (modular Android-app: `:pagecurl`-modul, Jetpack Compose + Kotlin)
**Henvendt til:** Senior Android Engineer for sparring om tverrsnittsproblematikk

---

## 1. Bakgrunn

Shelf Reader er en e-bok / lydbok-app med følgende kjente trekk:

- **3D page-curl engine:** Egen Kamigura-fork av `oleksandrbalan/pagecurl` v1.5.1 (Apache-2.0).
  - Custom `PageCurlState` med `Animatable<Edge>` for kurvatur-kontroll
  - `drawCurl` / `drawCurlFront` / `drawCurlBack` som bruker `Path` clipping + poly-to-poly transform
  - Tap- og drag-interaksjon, med `onCustomTap` for 3-soners layout (28 % venstre → bak, 44 % midt → meny, 28 % høyre → frem)
- **E-bok rendering:** Off-screen `WebView` som benytter seg av flerkolonnes-CSS
  (`column-width: 100vw`). Renderes til `Bitmap` → lagres i LRU-cache → tegnes i PageCurl.
- **Android Auto / MediaLibraryService:** Ferdig implementert for lydbøker (bortsett fra denne feilen).

---

## 2. Problembeskrivelse (nøyaktige symptomer)

| Enhet | SoC / GPU | Funksjonell status |
|---|---|---|
| **OnePlus 13** (kontroll) | Snapdragon 8 Elite (SM8750) / **Adreno 830** | ✅ ALT fungerer |
| **Motorola Edge 50 Pro** | Snapdragon 8s Gen 3 (SM8635) / **Adreno 750** | ❌ 3 separate feil |

### 2a. Symptom 1 (kritisk): Ingen interaktiv 3D-bøying under drag

**✅ OnePlus:**
- Drager man fingeren langsomt 50 % over skjermen → bøyningen stopper nøyaktig der.
- Man kan "holde" arket halvt oppe og justere kurvaturen dynamisk.
- Hver pixel med fingerbevegelse → ny `Edge`-posisjon → ny tegning.

**❌ Motorola:**
- 3D-effekten **er synlig**, men den er en **fast, predefinert animasjon** (som en video).
- Man kan **IKKE styre arkets kurvatur med fingeren** under drag.
- Følelsen: når man trykker og drar settes en `animateTo(targetEnd)` i gang med fixed duration. Arkets posisjon lytter IKKE til fingerens *nåværende* posisjon.

### 2b. Symptom 2: Dobble sider
Etter 2–6 sidevingninger dukker den samme siden opp igjen visuelt. `currentPage` øker, men innholdet på skjermen er det samme som forrige.

### 2c. Symptom 3: Fremdrift stopper helt
Til slutt, etter ca. 8–12 sidevingninger, går det ikke å bla videre uansett hvor mange ganger man trykker på høyre sonen eller drar.

---

## 3. Arkitektur — rendering pipeline (for diagnose)

```
┌──────────────────┐
│ Drag / Tap input │
└────────┬─────────┘
         │ Modifier-tre i PageCurl.kt: dragGesture → dragStartEnd
         ▼
┌───────────────────────────────────────────────────┐
│ detectCurlGestures / detectCustomDragGestures     │
│  → awaitFirstDown → awaitTouchSlopOrCancellation  │
│  → drag-loop: onDrag endrer posisjon pr. event    │
│  → NewEdgeCreator.Default.createNew(start, current)
└────────┬──────────────────────────────────────────┘
         │ scope.launch { edge.animateTo(target) }
         ▼
┌─────────────────────────────────────────────┐
│  PageCurlState.forward / backward (Animatable<Edge>)
│  → internalState.forward.value (Offset-pair top+bottom)
└────────┬────────────────────────────────────┘
         ▼
┌─────────────────────────────────────────────────────────────┐
│  key(updatedCurrent, forward.value, backward.value) {       │
│     content(pageIdx)  ← recomposition 60 fps i animasjon    │
│  }                                                           │
└────────┬────────────────────────────────────────────────────┘
         │
         │ Bitmap henting:
         ▼
┌────────────────────────────────────────────┐
│  PageBitmapCache (LRU, maxSize=8)          │
│   getSync(pageIdx) → cache hit → bruk      │
│   cache miss → suspend renderPage(pageIdx) │
└────────┬───────────────────────────────────┘
         ▼
┌────────────────────────────────────────────────────────────┐
│  HtmlPageRenderer. Off-screen WebView:                     │
│    · view målt med EXACTLY(pageWidth, pageHeight)          │
│    · lagt til DecorView med LayoutParams(pageW, pageH)      │
│    · translationX = -10000f  ← UTENFOR viewport             │
│    · CSS: column-width: 100vw; column-gap: 0                │
│    · evaluateJavascript( translateX(-(page * stride)) )     │
│    · 50ms setTimeout → WebView.draw(Canvas(bitmap))        │
│  pending: pendingRenderGen / pendingRenderPage /            │
│           pendingRenderCont  (3 GLOBALE variabler!)         │
└────────────────────────────────────────────────────────────┘
```

---

## 4. Nøkkelfiler (gir full kontekst)

| Modul | Fil | Relevans |
|---|---|---|
| `:reader` | [ReaderScreen.kt: RealBookSlideReader](file:///c:/Trae/Webshop/shelf/reader/src/main/java/com/shelf/reader/reader/ui/ReaderScreen.kt#L511-L700) | Caching, tap-sone, binding mellom `ui` og `curlState` |
| `:reader` | [HtmlPageRenderer.kt](file:///c:/Trae/Webshop/shelf/reader/src/main/java/com/shelf/reader/reader/engine/HtmlPageRenderer.kt) | Off-screen WebView + **den globale pending-race** |
| `:reader` | [PageBitmapCache.kt](file:///c:/Trae/Webshop/shelf/reader/src/main/java/com/shelf/reader/reader/engine/PageBitmapCache.kt) | LRU cache; ingen in-flight dedup |
| `:pagecurl` | [PageCurl.kt](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/PageCurl.kt#L88-L103) | Rekkefølgen på `dragGesture` → `tapGesture` modifier |
| `:pagecurl` | [DragStartEnd.kt](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/DragStartEnd.kt#L27-L29) | `forwardStartRect = Rect(0.5, 0, 1, 1).contains(start)` → **sannsynlig årsak til symptom 1** |
| `:pagecurl` | [DragCommonGesture.kt](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/DragCommonGesture.kt#L141-L169) | `detectCustomDragGestures` + touchSlop håndtering |
| `:pagecurl` | [TapGesture.kt](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/TapGesture.kt#L27) | `awaitFirstDown().also { it.consume() }` |
| `:pagecurl` | [PageCurlState.kt](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/PageCurlState.kt#L241-L263) | `dragTurnTo` — per-pointer animateTo |
| `:pagecurl` | [PageCurlState.kt animateTo finally](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/PageCurlState.kt#L427-L430) | `current=target` → deretter `reset()` rekkefølge |
| `:pagecurl` | [CurlDraw.kt](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/CurlDraw.kt#L35-L82) | Draw + lineLineIntersection fallback |
| `:reader` | [GpuDeviceProfile.kt](file:///c:/Trae/Webshop/shelf/reader/src/main/java/com/shelf/reader/reader/pageturn/GpuDeviceProfile.kt) | Kun Adreno 830 er detektert; Adreno 750 faller gjennom |

---

## 5. Hypoteser — sortert etter sannsynlighet

### 5.1. Hypotese A (SANNLIGHET — HØY → forklarer Symptom 1 100 %)

**Tittel:** `StartEndDragInteraction` sin forward-start-rektangel er for trang og treffer ikke startposisjonen på Motorola pga høye DPI + avrunding.

**Detaljer:**
- Standard config: `forward.start = rightHalf() = Rect(0.5f, 0.0f, 1.0f, 1.0f)` (sektor 0.5–1.0, dvs. høyre halvdel).
- Bruker plasserer fingeren i praksis på en Motorola-skjerm (1220 px bredde):
  ```
  Bredde 1220 px → høyere halvdel = 610–1220 px
  Fingerens nederste punkt = x = 606 px
  Fraksjon: 606 / 1220 = 0.496 721...
  ```
- `Rect(0.5, 0, 1, 1).contains(0.496, y)` → **FALSE**.
- Dermed: `getConfig(start, _)` returnerer `null` → drag **registreres aldri som forward drag**.
- Resultat: det eneste som skjer er at `TapGesture` fanger opp et "tap" i høyre sone (fordi bevegelsen er under touchSlop *ellers*) → `state.next()` → **fast keyframes-animasjon (400 ms)**. Ingen interaktiv bøying.

**Motbevis / hvorfor OnePlus 13 unngår det:** OnePlus 13 sin bredde er ~1440 px, og dens kalibrerte touch-registrerer gjerne på x+14 px pga større finger-pad-estimat, så brukeren havner oftere i > 0.5-fraksjonen.

### 5.2. Hypotese B (SANNLIGHET — HØY → forklarer Symptomer 2 + 3)

**Tittel:** Race condition i HtmlPageRenderer: **GLOBALE** `pendingRenderPage / pendingRenderGen / pendingRenderCont` blir overskrevet av neste render-request før JS har svart.

**Detaljer (tidsskrive):**
```
T=0ms   renderPage(1) begynner → set:
          pendingRenderGen = G
          pendingRenderPage = 1
          pendingRenderCont = Cont#1
        → evaluateJavascript(...) sender til Chromium

T=30ms  Brukeren trykker neste → renderPage(2) venter på renderMutex

T=40ms  renderMutex frigjøres → renderPage(2) begynner → OVERSKRIVER:
          pendingRenderPage = 2
          pendingRenderCont = Cont#2
        → evaluateJavascript(...) for page 2 sendes

T=110ms Chromium svarer på page 1 SIN request:
          onPageOffsetApplied(gen=G, pageIndex=1)
        → SJEKK: pageIndex(1) == pendingRenderPage(2)  ❌ FEIL
        → pendingRenderCont.resume kalles ALDRI for Cont#1

T=3000ms HtmlPageRenderer.renderPage(1) sin withTimeoutOrNull(3000L)
         utløser timeout → gir Bitmap.createBitmap(1220,2712,ARGB_8888) (TOMT!)
         → Dette TOMME bildet puttes i PageBitmapCache[1]

T=3100ms Neste gang man ser page 1: cache gir TOMT bilde.
         → Det som vises er gammelt side-0 innhold fra en tidligere frame
         → "dobble sider" → fremdrift til slutt stopper.
```

**OnePlus 13 unngår:** Chromium + Adreno 830 combo svarer vanligvis <40 ms på JS → request#1 sin onPageOffsetApplied kjører FØR request#2 begynner.

### 5.3. Hypotese C (SANNLIGHET — MIDDEL-HØY → bidrar til Symptom 1)

**Tittel:** TapGesture.consume() i awaitFirstDown + rekkefølgen drag → tap gjør at drag aldri ser pointer.

**Detaljer:**
- I [PageCurl.kt#L88-L103](file:///c:/Trae/Webshop/shelf/pagecurl/src/main/kotlin/eu/wewox/pagecurl/page/PageCurl.kt#L88-L103):
  ```
  Modifier
    .then(dragGestureModifier)
    .then(Modifier.tapGesture(config, scope, onTapForward, onTapBackward))
  ```
- Begge kaller `awaitFirstDown` i hvert sitt `awaitEachGesture`.
- Men `TapGesture` gjør `awaitFirstDown().also { it.consume() }` umiddelbart.
- I Compose er konsumering i pointerInput per-modifier. Men dersom en Compose dispatch-runde plasserer Tap *før* Drag (forskjellig baseline pga. Motorola sin MyUX-framework touch event-timing), kan Tap consumere eventet FØR dragGesture rekorderer start.
- Resultat: drag registreres aldri → kun tap → animasjon.

### 5.4. Hypotese D (SANNLIGHET — MIDDEL → bidrar til Symptom 1)

**Tittel:** `viewConfiguration.touchSlop` er større på Motorola pga skjerm-kalibrering.

**Detaljer:**
- `awaitTouchSlopOrCancellation` i standard versjon bruker `ViewConfiguration.get(context).scaledTouchSlop`.
- MyUX skrur ofte opp touchSlop for å unngå utilsiktede trykk i skrivebordsvelgeren.
- Hvis touchSlop er 1.7× større, vil subtile drag (som brukes i "quiet" curl på en bok) faller tilbake til tap.

### 5.5. Hypotese E (SANNLIGHET — LAV-MIDDEL → bidrar til Symptom 2 timing)

**Tittel:** HWUI dirty-rect rejection → WebView.draw(Canvas) henter stale/blankt bilde.

**Detaljer:**
- Se Logcat seksjon 6.2. WebView er på `translationX=-10000f`. Når den oppdateres er dirty rect på ca. [-20000, 0, -18780, 2711].
- Adreno 750 sin Vulkan HWUI sier "doesn't intersect with [0,0,1220,2712]" → hopper over å bygge display-list.
- Mens WebView sin interne CPU-draw er OK, når vi kaller `webView.draw(canvas)` vil noen tilfeller få en ikke-oppdatert HWUI-layer → blanke pikselområder.
- Kombinert med Hypotese B → flere tomme bitmaps i cache.

---

## 6. Logcat snippets fra Motorola Edge 50 Pro

### 6.1. GPU / Vulkan driver-info (bekrefter AdrenoVK-0)

```
2026-08-13 17:59:57.028 25233-25886 AdrenoVK-0   com.shelf.reader  I  Build Config            : S P 14.1.4 AArch64
2026-08-13 17:59:57.028 25233-25886 AdrenoVK-0   com.shelf.reader  I  Driver Path             : /vendor/lib64/hw/vulkan.adreno.so
2026-08-13 17:59:57.028 25233-25886 AdrenoVK-0   com.shelf.reader  I  Driver Version          : 0676.76.2
2026-08-13 17:59:57.028 25233-25886 AdrenoVK-0   com.shelf.reader  I  PFP                     : 0x01200182
2026-08-13 17:59:57.028 25233-25886 AdrenoVK-0   com.shelf.reader  I  ME                      : 0x00000000
2026-08-13 17:59:57.134 25233-25867 VideoCapabilities com.shelf.reader W  Unsupported mime image/vnd.android.heic
```

### 6.2. HWUI dirty rects (ADRENO forkaster områder utenfor viewport)

```
2026-08-13 17:59:59.540 25233-25297 HWUI    W  Dirty -20000.00  0.00 -18780.00 2711.00 doesn't intersect with 0 0 1220 2712 ?
2026-08-13 18:00:02.171 25233-25297 HWUI    W  Dirty -20000.00  0.00 -18780.00 2711.00 doesn't intersect with 0 0 1220 2712 ?
2026-08-13 18:00:04.047 25233-25297 HWUI    W  Dirty -20000.00  0.00 -18780.00 2711.00 doesn't intersect with 0 0 1220 2712 ?
2026-08-13 18:00:05.728 25233-25297 HWUI    W  Dirty -20000.00  0.00 -18780.00 2711.00 doesn't intersect with 0 0 1220 2712 ?
2026-08-13 18:00:05.797 25233-25297 HWUI    W  Dirty -20000.00  0.00 -18780.00 2711.00 doesn't intersect with 0 0 1220 2712 ?
2026-08-13 18:00:05.813 25233-25297 HWUI    W  Dirty -20000.00  0.00 -18780.00 2711.00 doesn't intersect with 0 0 1220 2712 ?
```

Kommentar: `1220` er Motorola sin fysiske skjermbredde i px. Dirty rect er 1220 px bred, MEN start = −20000. Driveren sier at det ikke er noe geometriskt overlapp med det synlige området. Symptom: WebView sin CPU-draw er OK, MEN HWUI display-list oppdateres ikke hele veien.

### 6.3. HtmlPageRenderer (ujevne intervaller = WebView draw timing er ustabil)

```
2026-08-13 17:59:57.226 ... HtmlPageRenderer  D  onPageFinished: totalPages=1 cssW=433 physW=1220 raw=1
2026-08-13 17:59:59.541 ... HtmlPageRenderer  D  onPageFinished: totalPages=1 cssW=433 physW=1220 raw=1    ← 2,3 s etter forrige
2026-08-13 18:00:02.173 ... HtmlPageRenderer  D  onPageFinished: totalPages=1 cssW=433 physW=1220 raw=1    ← 2,6 s ← ujevn
2026-08-13 18:00:04.033 ... HtmlPageRenderer  D  onPageFinished: totalPages=1 cssW=433 physW=1220 raw=1    ← 1,8 s ←
2026-08-13 18:00:05.729 ... HtmlPageRenderer  D  onPageFinished: totalPages=2 cssW=433 physW=1220 raw=2    ← 1,7 s ←
```

Kommentar: på OnePlus 13 skjer samme linjer typisk hvert 350–450 ms. Motorola er 5–7× tregere i praksis, noe som gjør Hypotese B (pending race) større sannsynlighet.

---

## 7. Spørsmål til deg (for å rette debuggingen)

1. **StartEndDrag → Rect(0.5,0,1,1):**
   - Er det kjent at `StartEndDragInteraction` sin `forward.start = rightHalf()` faller platt på enheter med høy DPI der fingeren havner akkurat på venstre side av midten?
   - Er `GestureDragInteraction` (basert på retningsbestemmelse, ikke rektangel) et tryggere fallback for cross-device?
   - Alternativ: utvide `forward.start` til `Rect(0.4, 0, 1.0, 1.0)` – en 10 % buffer mot venstre?

2. **Tap konsum-rekkefølge i pointerInput:**
   - Rekkefølgen `.then(drag).then(tap)` — hvorvidt en vendt rekkefølge `.then(tap).then(drag)` ville gjort at drag sjanser bedre for å se events?
   - I `TapGesture` gjør vi `awaitFirstDown().also { it.consume() }` umiddelbart. Er det trygt å *ikke* consume før vi faktisk vet at det var et ekte tap (etter at touchSlop er passert)?

3. **ViewConfiguration touchSlop:**
   - Noen OEM-er (Samsung, Motorola) skrur opp touchSlop for å unngå utilsiktede tastetrykk.
   - Er det en best practice å multiplisere touchSlop med 0.7x i applikasjoner der drag er viktig (lesere, tegning)?
   - Finnes det en myk landing som `coerceIn(8, touchSlop)` for å forhindre at ekstreme verdier ødelegger?

4. **pendingRender i HtmlPageRenderer (Symptom 2 + 3):**
   - Er det best å erstatte de 3 globale variablene med:
     - (A) `Mutex<Map<Pair<Long,Int>, CancellableContinuation>>` — slå opp både på generasjon og pageIndex
     - (B) En `actor / Channel<RenderRequest>` som serialiserer requests 1 om gangen
   - Vil en `Mutex` rundt JS-request plus Map for per-(gen,page)-dispatch være nok? Behøver vi egentlig ikke Channel da mutex allerede serialiserer?

5. **Off-screen WebView best practice:**
   - Foreløpig: legges til DecorView med størrelse = pageW×pageH, deretter `translationX=-10000f`.
   - Alternativer:
     - (A) `LayoutParams(0, 0)` på View i DecorView, men `measure(EXACTLY W, EXACTLY H)` + `layout(0,0,W,H)` uavhengig?
     - (B) `PixelCopy` fra en `SurfaceView` som holder WebView?
     - (C) `LAYER_TYPE_SOFTWARE` for å omgå HWUI dirty-rect sjekken?
   - Vil (A) være nok til å stoppe HWUI dirty-rect advarslene?

---

## 8. Ting vi *ikke* vil gjøre (drastisk)

- ❌ Skrive om hele PageCurl til nytt bibliotek.
- ❌ Bytte av renderingspipeline (fjerne WebView og skrive egen EPUB-renderer).
- ❌ Skru av animasjoner / flat side-overgang på Motorola som fallback.
- Ønsker minimal kode-endring, cross-platform robust.

---

## 9. Kortfattet plan hvis ingen innspill (for kontekst)

Hvis vi ikke får ytterligere tilbakemeldinger, tar vi følgende konservativ tilnærming:

1. **Fix A:** `StartEndDrag` → `GestureDrag` + `forward.startRect` utvides til 0.4 buffer.
2. **Fix B:** pendingRender → `Map<Pair<Long,Int>,Cont>` i HtmlPageRenderer.
3. **Fix C:** `TapGesture` fjerner `.consume()` fra down-event; vent til bekreftet tap.
4. **Fix D:** `touchSlop` bruker 0.7× standard verdi (coerceAtLeast(8 px)).
5. **Fix E:** WebView får `LayoutParams(0, 0)` men behold måling/layout.

Hver endring er isolert, reversibel, og kan commits separat.

---

— SLUTT PÅ DOKUMENT —
