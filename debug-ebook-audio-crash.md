# Debug Session: ebook-audio-crash

- Status: OPEN
- Date: 2026-07-30
- Scope: EPUB/ebook open failures, SAF access failures, audiobook startup crash/freeze

## Symptoms
- All ebooks fail with "Kan ikke åpne boken. Formatet støttes ikke eller filen er skadet".
- Audiobooks still crash or freeze when starting from "Hør nå".

## Falsifiable Hypotheses
1. SAF permissions are not actually persisted/restored for existing imported books, so `fileUri` opens fail at runtime even though import succeeded.
2. Book loading falls back to the generic binary/error path because the parser receives the wrong source stream or a URI/path mismatch after re-import.
3. Imported ebooks store inconsistent `filePath`/`fileUri` combinations, so open logic checks the wrong location and reports a misleading parse error.
4. Audiobook startup still violates Android 14/15 foreground/media-session timing rules in the real runtime path, causing a crash before playback becomes stable.
5. Audiobook crashes/freeze come from null/invalid media URI or service/player race during `ACTION_LOAD_BOOK` rather than from the UI button itself.

## Evidence Plan
- Add instrumentation around import permission acquisition/restoration.
- Add instrumentation around reader open path selection and parser inputs/results.
- Add instrumentation around audiobook intent launch, service foreground transition, media item creation, and player prepare/play.
- Reproduce on device/emulator, inspect logs, then implement minimal fix from evidence.
