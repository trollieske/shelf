# 📋 Fullverdig Endringslogg (Changelog)

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
