package com.shelf.reader.library.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.core.domain.model.AutoShelf
import com.shelf.reader.core.domain.model.DarkModePref
import com.shelf.reader.core.domain.model.LibraryViewType
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.core.dispatchers.DispatcherProvider
import com.shelf.reader.library.cover.CoverRepository
import com.shelf.reader.library.data.BookImportRepository
import com.shelf.reader.library.mapper.DomainMappers.toBookVisual
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.BookEntity
import com.shelf.reader.data.local.entity.BookTypeEntity
import com.shelf.reader.data.local.entity.ShelfBookCrossRef
import com.shelf.reader.data.local.entity.ShelfEntity
import com.shelf.reader.data.local.entity.ShelfTypeEntity
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.designsystem.components.BookVisual
import com.shelf.reader.designsystem.components.ShelfRow
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class LibrarySort(val storage: String) {
    DATE_ADDED("date_added"),
    TITLE("title"),
    TITLE_ASC("title_asc"),
    TITLE_DESC("title_desc"),
    AUTHOR("author"),
    PROGRESS("progress");

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.storage == s } ?: DATE_ADDED
    }
}

enum class LibraryFilter(val storage: String) {
    ALL("all"),
    IN_PROGRESS("in_progress"),
    UNREAD("unread"),
    FINISHED("finished"),
    EBOOKS("ebooks"),
    AUDIOBOOKS("audiobooks"),
    FAVORITES("favorites");

    companion object {
        fun from(s: String?) = entries.firstOrNull { it.storage == s } ?: ALL
    }
}

data class LibraryUiState(
    val viewType: LibraryViewType = LibraryViewType.SHELF,
    val darkMode: DarkModePref = DarkModePref.FOLLOW_SYSTEM,
    val dynamicColors: Boolean = false,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.DATE_ADDED,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val isLoading: Boolean = true,
    val autoShelves: List<ShelfRow> = emptyList(),
    val userShelves: List<ShelfEntity> = emptyList(),
    val shelvesForBook: Map<Long, List<ShelfEntity>> = emptyMap(),
    val flatGridBooks: List<BookVisual> = emptyList(),
    val flatListBooks: List<BookEntity> = emptyList(),
    val totalBookCount: Int = 0,
    val ebookCount: Int = 0,
    val inProgressCount: Int = 0,
    val finishedCount: Int = 0,
    val audiobookCount: Int = 0,
    val libraryFormatFilterEnabled: Boolean = true,
    val libraryTabCountsEnabled: Boolean = true,
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
    val searchQuery: StateFlow<String> = queryFlow.asStateFlow()

    private val sortFlow = MutableStateFlow(LibrarySort.DATE_ADDED)
    private val filterFlow = MutableStateFlow(LibraryFilter.ALL)
    private val overrideViewTypeFlow = MutableStateFlow<LibraryViewType?>(null)

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
        sortFlow,
        filterFlow,
        prefs.libraryViewType,
        overrideViewTypeFlow,
        prefs.darkMode,
        prefs.dynamicColors,
        prefs.libraryFormatFilterEnabled,
        prefs.libraryTabCountsEnabled
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val prefView = args[3] as LibraryViewType
        val overrideView = args[4] as LibraryViewType?
        Params(
            query = args[0] as String,
            sort = args[1] as LibrarySort,
            filter = args[2] as LibraryFilter,
            view = overrideView ?: prefView,
            dark = args[5] as DarkModePref,
            dynamic = args[6] as Boolean,
            formatFilterEnabled = args[7] as Boolean,
            tabCountsEnabled = args[8] as Boolean
        )
    }
        .flatMapLatest { p -> buildStateFlow(p) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState()
        )

    private data class Params(
        val query: String, val sort: LibrarySort, val filter: LibraryFilter,
        val view: LibraryViewType, val dark: DarkModePref, val dynamic: Boolean,
        val formatFilterEnabled: Boolean, val tabCountsEnabled: Boolean
    )

    private fun buildStateFlow(p: Params): Flow<LibraryUiState> =
        combine(
            booksMatching(p.query, p.sort, p.filter),
            progressPercents(),
            db.bookDao().observeInProgress(),
            db.bookDao().observeFinished(),
            db.bookDao().observeAudiobooks(),
            userShelves(),
            db.bookDao().observeAll()
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val matching = args[0] as List<BookEntity>
            @Suppress("UNCHECKED_CAST")
            val prog = args[1] as Map<Long, Float>
            @Suppress("UNCHECKED_CAST")
            val inProg = args[2] as List<BookEntity>
            @Suppress("UNCHECKED_CAST")
            val fin = args[3] as List<BookEntity>
            @Suppress("UNCHECKED_CAST")
            val audio = args[4] as List<BookEntity>
            @Suppress("UNCHECKED_CAST")
            val userSh = args[5] as List<Pair<com.shelf.reader.data.local.entity.ShelfEntity, List<BookEntity>>>
            @Suppress("UNCHECKED_CAST")
            val allUnfiltered = args[6] as List<BookEntity>
            val filesDir = getApplication<Application>().filesDir
            val visual: (BookEntity) -> BookVisual = { toBookVisual(it, prog[it.id], filesDir) }

            val totalActive = allUnfiltered.filter { !it.isDeleted }
            val audioActive = audio.filter { !it.isDeleted }
            val totalCount = totalActive.size
            val abCount = audioActive.size
            val ebCount = (totalCount - abCount).coerceAtLeast(0)

            val showSearch = p.query.isNotBlank()
            val auto = listOfNotNull(
                if (!showSearch) shelfRow(AutoShelf.RECENTLY_ADDED.id, "Nylig lagt til",
                    matching.take(8), visual) else null,
                if (!showSearch) shelfRow(AutoShelf.IN_PROGRESS.id, "Pågår",
                    inProg.take(7), visual) else null,
                if (!showSearch) shelfRow(AutoShelf.FINISHED.id, "Ferdig",
                    fin, visual) else null,
                if (!showSearch) shelfRow(AutoShelf.AUDIOBOOKS.id, "Lydbøker",
                    audioActive.take(12), visual) else null,
                if (!showSearch) shelfRow(AutoShelf.EBOOKS.id, "Ebøker",
                    totalActive.filter { it.type != BookTypeEntity.AUDIOBOOK }.take(12), visual) else null
            ) + userSh.map { (shelf, booksInShelf) ->
                ShelfRow(
                    id = "user_${shelf.id}",
                    label = shelf.name,
                    books = booksInShelf.map(visual)
                )
            }

            val bookShelves = mutableMapOf<Long, MutableList<com.shelf.reader.data.local.entity.ShelfEntity>>()
            userSh.forEach { (shelf, booksInShelf) ->
                booksInShelf.forEach { book ->
                    bookShelves.getOrPut(book.id) { mutableListOf() }.add(shelf)
                }
            }

            LibraryUiState(
                viewType = p.view,
                darkMode = p.dark,
                dynamicColors = p.dynamic,
                query = p.query,
                sort = p.sort,
                filter = p.filter,
                isLoading = false,
                autoShelves = auto,
                userShelves = userSh.map { it.first },
                shelvesForBook = bookShelves,
                flatGridBooks = matching.map(visual),
                flatListBooks = matching,
                totalBookCount = totalCount,
                ebookCount = ebCount,
                inProgressCount = inProg.size,
                finishedCount = fin.size,
                audiobookCount = abCount,
                libraryFormatFilterEnabled = p.formatFilterEnabled,
                libraryTabCountsEnabled = p.tabCountsEnabled
            )
        }
            .catch { emit(LibraryUiState(error = it.message, isLoading = false)) }
            .flowOn(dispatchers.default)

    private fun shelfRow(
        id: String, label: String,
        books: List<BookEntity>, mapper: (BookEntity) -> BookVisual
    ) = ShelfRow(id = id, label = label, books = books.map(mapper))

    private fun progressPercents(): Flow<Map<Long, Float>> =
        db.progressDao().observeAll().map { rows ->
            rows.associate { it.bookId to it.progressPercent }
        }

    private fun booksMatching(
        query: String,
        sort: LibrarySort,
        filter: LibraryFilter
    ): Flow<List<BookEntity>> {
        val base = if (query.isNotBlank()) db.bookDao().search(query) else db.bookDao().observeAll()
        return base.map { list ->
            val filtered = when (filter) {
                LibraryFilter.ALL -> list
                LibraryFilter.IN_PROGRESS -> list.filter { (it.dateFinished == null) }
                LibraryFilter.UNREAD -> list.filter { it.dateFinished == null }
                LibraryFilter.FINISHED -> list.filter { it.dateFinished != null }
                LibraryFilter.EBOOKS -> list.filter { it.type != BookTypeEntity.AUDIOBOOK }
                LibraryFilter.AUDIOBOOKS -> list.filter { it.type != BookTypeEntity.EBOOK }
                LibraryFilter.FAVORITES -> list.filter { it.isFavorite }
            }
            when (sort) {
                LibrarySort.DATE_ADDED -> filtered.sortedWith(compareByDescending<BookEntity> { it.dateAdded }.thenBy { it.id })
                LibrarySort.TITLE, LibrarySort.TITLE_ASC -> filtered.sortedWith(compareBy<BookEntity> { it.sortTitle.lowercase() }.thenBy { it.id })
                LibrarySort.TITLE_DESC -> filtered.sortedWith(compareByDescending<BookEntity> { it.sortTitle.lowercase() }.thenBy { it.id })
                LibrarySort.AUTHOR -> filtered.sortedWith(compareBy<BookEntity> { it.sortAuthor.lowercase() }.thenBy { it.sortTitle.lowercase() }.thenBy { it.id })
                LibrarySort.PROGRESS -> filtered.sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt ?: 0L }.thenByDescending { it.dateAdded }.thenBy { it.id })
            }
        }
    }

    private fun userShelves(): Flow<List<Pair<ShelfEntity, List<BookEntity>>>> =
        db.shelfDao().observeUserShelves().flatMapLatest { shelves ->
            if (shelves.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(shelves.map { s ->
                db.shelfDao().observeShelfBooks(s.id).map { books -> s to books }
            }) { it.toList() }
        }

    // --- UI actions ---

    fun setQuery(q: String) { queryFlow.value = q.trim() }
    fun setSort(s: LibrarySort) { sortFlow.value = s }
    fun setFilter(f: LibraryFilter) { filterFlow.value = f }

    fun setViewType(v: LibraryViewType) {
        overrideViewTypeFlow.value = v
        viewModelScope.launch(dispatchers.io) { prefs.setLibraryViewType(v) }
    }

    fun clearAllBooks() {
        viewModelScope.launch(dispatchers.io) {
            db.bookDao().deleteAll()
        }
    }

    fun toggleFavorite(bookId: Long) {
        viewModelScope.launch(dispatchers.io) {
            val b = db.bookDao().getById(bookId) ?: return@launch
            db.bookDao().update(b.copy(
                isFavorite = !b.isFavorite,
                lastModifiedAt = System.currentTimeMillis()
            ))
        }
    }

    fun markFinished(bookId: Long) {
        viewModelScope.launch(dispatchers.io) {
            val b = db.bookDao().getById(bookId) ?: return@launch
            val now = System.currentTimeMillis()
            val isFinishedNow = b.dateFinished == null
            db.bookDao().update(b.copy(
                dateFinished = if (isFinishedNow) now else null,
                lastModifiedAt = now
            ))
            db.progressDao().upsertForBook(
                bookId = bookId,
                updater = { it.copy(progressPercent = if (isFinishedNow) 1f else it.progressPercent, updatedAt = now) },
                creator = { com.shelf.reader.data.local.entity.ReadingProgressEntity(bookId = bookId, progressPercent = 1f) }
            )
        }
    }

    fun delete(bookId: Long) {
        viewModelScope.launch(dispatchers.io) {
            db.bookDao().softDelete(bookId)
        }
    }

    fun addToShelf(shelfId: Long, bookId: Long) {
        viewModelScope.launch(dispatchers.io) {
            db.shelfDao().insertCrossRef(ShelfBookCrossRef(shelfId = shelfId, bookId = bookId))
        }
    }

    fun createShelf(name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        viewModelScope.launch(dispatchers.io) {
            val now = System.currentTimeMillis()
            db.shelfDao().insert(
                ShelfEntity(
                    name = trimmed,
                    sortName = trimmed,
                    type = ShelfTypeEntity.USER,
                    coverColor = (0xFF000000.toInt() or (trimmed.hashCode() and 0xFFFFFF)),
                    icon = "folder_open",
                    position = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        return 0L
    }

    fun renameShelf(id: Long, newName: String) {
        viewModelScope.launch(dispatchers.io) {
            val existing = db.shelfDao().getById(id) ?: return@launch
            db.shelfDao().update(
                existing.copy(
                    name = newName,
                    sortName = newName,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteShelf(id: Long) {
        viewModelScope.launch(dispatchers.io) {
            db.shelfDao().deleteCrossRefsByShelfId(id)
            db.shelfDao().deleteById(id)
        }
    }

    fun assignBookToShelf(bookId: Long, shelfId: Long) {
        viewModelScope.launch(dispatchers.io) {
            try {
                db.shelfDao().insertCrossRef(
                    ShelfBookCrossRef(shelfId = shelfId, bookId = bookId)
                )
            } catch (_: SQLiteConstraintException) {
            }
        }
    }

    fun removeBookFromShelf(bookId: Long, shelfId: Long) {
        viewModelScope.launch(dispatchers.io) {
            db.shelfDao().deleteCrossRef(
                ShelfBookCrossRef(shelfId = shelfId, bookId = bookId)
            )
        }
    }
}
