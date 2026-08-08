# Fix for ANR in MainActivity (Library Screen)

The application is experiencing an ANR (Application Not Responding) during startup, specifically when the Library screen is being loaded. Analysis of the logs and codebase reveals a tight infinite loop in the `LibraryViewModel`'s state production pipeline.

## User Review Required

> [!IMPORTANT]
> The fix involves moving side-effects (database updates) out of the data flow pipeline. This is a critical architectural fix to prevent infinite loops that saturate the CPU and database.

## Proposed Changes

### [Component Name] library

#### [MODIFY] [LibraryViewModel.kt](file:///C:/Users/cKlappy/Documents/trae_projects/Book/library/src/main/java/com/shelf/reader/library/viewmodel/LibraryViewModel.kt)

- **Remove Side-Effect from `combine`**: The logic that triggers cover generation (`missing.filter` and `viewModelScope.launch`) will be removed from the `buildStateFlow`'s `combine` block.
- **Introduce Dedicated Cover Worker**: A new `launch` block in `init` will observe the book list and trigger cover generation for missing covers.
- **Use `collectLatest`**: This ensures that if a new book list arrives while we are processing covers, the old processing is cancelled and restarted with the latest data, preventing redundant work and job accumulation.
- **Controlled Processing**: We will ensure that cover generation doesn't immediately re-trigger itself in a way that blocks the UI thread.

## Verification Plan

### Automated Tests
- I will run `:library:assembleDebug` to ensure the changes compile.

### Manual Verification
- Deploy the app and verify that the Library screen loads smoothly without ANR.
- Verify that missing book covers are still being generated in the background (visible as covers appearing on books).
