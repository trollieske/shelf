package com.shelf.reader.library.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.core.domain.model.DarkModePref
import com.shelf.reader.core.domain.model.LibrarySortMode
import com.shelf.reader.core.domain.model.SortDirection
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.library.cover.CoverRepository
import com.shelf.reader.library.mapper.DomainMappers.toBookVisual
import com.shelf.reader.library.sort.LibrarySorter
import com.shelf.reader.library.R
import com.shelf.reader.library.sort.ResumeSelector
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.BookEntity
import com.shelf.reader.data.local.entity.BookTypeEntity
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.designsystem.components.BookVisual
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Locked per destination: Books = kun ebøker, Audio = kun lydbøker. Ingen kryssmodus-filter. */
sealed class LibraryMode {
    data object Books : LibraryMode()
    data object Audio : LibraryMode()
}

/** Tynn fortsett-linje: tittel + prosent (ebok) eller gjenstående tid (lydbok). */
data class ResumeItem(
    val bookId: Long,
    val title: String,
    val author: String,
    val detail: String,
    val coverPath: String? = null
)

/** Rutenett-oppføringer: full-bredde seksjonsetikett (kun HYLLE) eller bokomslag. */
sealed interface GridEntry {
    data class SectionLabel(val text: String) : GridEntry
    data class BookEntry(val book: BookVisual) : GridEntry
}

data class LibraryUiState(
    val query: String = "",
    val sortMode: LibrarySortMode = LibrarySortMode.HYLLE,
    val direction: SortDirection = SortDirection.ASC,
    val isLoading: Boolean = true,
    val gridEntries: List<GridEntry> = emptyList(),
    val flatGridBooks: List<BookVisual> = emptyList(),
    val resumeEbooks: List<ResumeItem> = emptyList(),
    val resumeAudios: List<ResumeItem> = emptyList(),
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    application: Application,
    val prefs: UserPreferencesRepository = UserPreferencesRepository(application.applicationContext),
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val db = ShelfDatabase.getInstance(application.applicationContext)

    private val queryFlow = MutableStateFlow("")
    private val modeFlow = MutableStateFlow<LibraryMode>(LibraryMode.Books)

    init {
        viewModelScope.launch(dispatchers.io) {
            val app = getApplication<Application>()
            val coversDir = java.io.File(app.filesDir, "covers")
            val allBooks = runCatching { db.bookDao().getAllOnce() }.getOrElse { emptyList() }
            val missing = allBooks.filter { b ->
                val cp = b.coverPath
                (cp.isNullOrBlank() || !java.io.File(cp).exists()) &&
                        !java.io.File(coversDir, "book_${b.id}.webp").exists()
            }
            if (missing.isNotEmpty()) {
                val coverRepo = CoverRepository(app, db, dispatchers)
                missing.take(10).forEach { b ->
                    runCatching { coverRepo.coverFileFor(b) }
                }
            }
        }
    }

    val state: StateFlow<LibraryUiState> = combine(
        queryFlow,
        modeFlow,
        prefs.booksSortMode,
        prefs.audioSortMode,
        prefs.booksSortDirection,
        prefs.audioSortDirection
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val mode = args[1] as LibraryMode
        Params(
            query = args[0] as String,
            mode = mode,
            sortMode = if (mode == LibraryMode.Books) args[2] as LibrarySortMode else args[3] as LibrarySortMode,
            direction = if (mode == LibraryMode.Books) args[4] as SortDirection else args[5] as SortDirection
        )
    }
        .flatMapLatest { p -> buildStateFlow(p) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState()
        )

    private data class Params(
        val query: String,
        val mode: LibraryMode,
        val sortMode: LibrarySortMode,
        val direction: SortDirection
    )

    private fun buildStateFlow(p: Params): Flow<LibraryUiState> =
        combine(
            booksMatching(p.query, p.mode),
            progressRows(),
            db.bookDao().observeAll()
        ) { matching, rows, all ->
            val filesDir = getApplication<Application>().filesDir
            val pct: (Long) -> Float = { rows[it]?.progressPercent ?: 0f }
            val visual: (BookEntity) -> BookVisual = { toBookVisual(it, pct(it.id), filesDir) }

            val totalActive = all.filter { !it.isDeleted }

            // ── Sorting: single source of truth (LibrarySorter) ──
            val sortBooks = matching.map { b ->
                com.shelf.reader.library.sort.SortBook(
                    id = b.id,
                    title = b.title,
                    sortTitle = b.sortTitle,
                    author = b.author,
                    sortAuthor = b.sortAuthor,
                    series = b.series,
                    seriesIndex = b.seriesIndex,
                    seriesIndexResolved = b.seriesIndex ?: LibrarySorter.parseSeriesIndex(b.series),
                    dateAdded = b.dateAdded,
                    lastActivity = rows[b.id]?.updatedAt ?: b.lastOpenedAt ?: 0L,
                    updatedAt = rows[b.id]?.updatedAt ?: b.lastModifiedAt,
                    isAudio = b.type == BookTypeEntity.AUDIOBOOK
                )
            }
            val sorted = LibrarySorter.sort(sortBooks, p.sortMode, p.direction)
            val byId = matching.associateBy { it.id }
            val sortedEntities = sorted.mapNotNull { byId[it.id] }

            val showLabels = p.sortMode == LibrarySortMode.HYLLE && p.query.isBlank()
            val labels = if (showLabels) LibrarySorter.sectionLabels(sorted) else List(sorted.size) { null }

            val gridEntries = buildList {
                sortedEntities.forEachIndexed { i, entity ->
                    labels[i]?.let { add(GridEntry.SectionLabel(it)) }
                    add(GridEntry.BookEntry(visual(entity)))
                }
            }
            val flatGridBooks = sortedEntities.map(visual).distinctBy { it.id }

            // ── Fortsett-linje: smart multi-bok kandidater per fane ──
            val resumeInputs = totalActive.map { b ->
                ResumeSelector.ResumeBook(
                    id = b.id,
                    title = b.title,
                    author = b.author,
                    progressPercent = pct(b.id),
                    positionMs = rows[b.id]?.positionMs ?: 0L,
                    durationMs = b.durationMs ?: 0L,
                    isAudio = b.type == BookTypeEntity.AUDIOBOOK,
                    lastActivity = rows[b.id]?.updatedAt ?: b.lastOpenedAt ?: 0L,
                    updatedAt = rows[b.id]?.updatedAt ?: b.lastModifiedAt,
                    dateFinished = b.dateFinished,
                    isDeleted = b.isDeleted
                )
            }
            val remainingLabel: (Long) -> String = { ms ->
                val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(1)
                val h = totalMin / 60
                val m = totalMin % 60
                val timeText = if (h > 0) "${h}h ${m}min" else "${m}min"
                getApplication<Application>().getString(R.string.lib_remaining, timeText)
            }
            val ebookResume = ResumeSelector.select(resumeInputs, wantAudio = false, remainingLabel = remainingLabel)
            val audioResume = ResumeSelector.select(resumeInputs, wantAudio = true, remainingLabel = remainingLabel)

            val allActiveById = totalActive.associateBy { it.id }
            fun resolvedCover(bookId: Long): String? {
                val b = allActiveById[bookId] ?: return null
                val direct = b.coverPath?.takeIf { java.io.File(it).exists() }
                return direct ?: java.io.File(filesDir, "covers/book_${b.id}.webp")
                    .takeIf { it.exists() }?.absolutePath
            }

            LibraryUiState(
                query = p.query,
                sortMode = p.sortMode,
                direction = p.direction,
                isLoading = false,
                gridEntries = gridEntries,
                flatGridBooks = flatGridBooks,
                resumeEbooks = ebookResume.map { r ->
                    ResumeItem(r.bookId, r.title, r.author, r.detail, resolvedCover(r.bookId))
                },
                resumeAudios = audioResume.map { r ->
                    ResumeItem(r.bookId, r.title, r.author, r.detail, resolvedCover(r.bookId))
                },
                error = null
            )
        }
            .catch { emit(LibraryUiState(error = it.message, isLoading = false)) }
            .flowOn(dispatchers.default)

    private fun progressRows(): Flow<Map<Long, com.shelf.reader.data.local.entity.ReadingProgressEntity>> =
        db.progressDao().observeAll().map { rows ->
            rows.associate { it.bookId to it }
        }

    private fun booksMatching(
        query: String,
        mode: LibraryMode
    ): Flow<List<BookEntity>> {
        val base = if (query.isNotBlank()) db.bookDao().search(query) else db.bookDao().observeAll()
        return base.map { list ->
            when (mode) {
                LibraryMode.Books -> list.filter { it.type != BookTypeEntity.AUDIOBOOK }
                LibraryMode.Audio -> list.filter { it.type == BookTypeEntity.AUDIOBOOK }
            }
        }
    }

    // --- UI actions ---

    fun setQuery(q: String) { queryFlow.value = q.trim() }

    fun setMode(m: LibraryMode) { modeFlow.value = m }

    /** Sort Rail: persists per media tab. Default/invalid value is HYLLE. */
    fun setSortMode(mode: LibrarySortMode) {
        viewModelScope.launch(dispatchers.io) {
            if (modeFlow.value == LibraryMode.Books) prefs.setBooksSortMode(mode)
            else prefs.setAudioSortMode(mode)
        }
    }

    /** ⇅ toggles the persisted direction for the current tab. */
    fun toggleSortDirection() {
        viewModelScope.launch(dispatchers.io) {
            val isBooks = modeFlow.value == LibraryMode.Books
            val current = (if (isBooks) prefs.booksSortDirection else prefs.audioSortDirection).firstOrNull()
            val next = if (current == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
            if (isBooks) prefs.setBooksSortDirection(next) else prefs.setAudioSortDirection(next)
        }
    }

    fun delete(bookId: Long) {
        viewModelScope.launch(dispatchers.io) {
            db.bookDao().softDelete(bookId)
        }
    }
}
