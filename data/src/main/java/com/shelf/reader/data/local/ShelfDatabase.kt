package com.shelf.reader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.shelf.reader.data.local.dao.*
import com.shelf.reader.data.local.entity.*

@Database(
    entities = [
        BookEntity::class,
        AudioTrackEntity::class,
        ShelfEntity::class,
        ShelfBookCrossRef::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        FtpServerEntity::class,
        DownloadTaskEntity::class,
        CachedPathEntity::class,
        SyncHistoryEntity::class,
        SmbServerEntity::class,
        WebdavServerEntity::class,
        TorrentDownloadEntity::class,
        WorkEntity::class,
        WorkEditionEntity::class,
        HandoffLinkEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun audioTrackDao(): AudioTrackDao
    abstract fun shelfDao(): ShelfDao
    abstract fun progressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun ftpServerDao(): FtpServerDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun cachedPathDao(): CachedPathDao
    abstract fun syncHistoryDao(): SyncHistoryDao
    abstract fun smbServerDao(): SmbServerDao
    abstract fun webdavServerDao(): WebdavServerDao
    abstract fun torrentDownloadDao(): TorrentDownloadDao
    abstract fun workDao(): com.shelf.reader.data.local.dao.WorkDao
    abstract fun workEditionDao(): com.shelf.reader.data.local.dao.WorkEditionDao
    abstract fun handoffLinkDao(): com.shelf.reader.data.local.dao.HandoffLinkDao
    abstract fun workWithEditionsDao(): com.shelf.reader.data.local.dao.WorkWithEditionsDao

    companion object {
        private const val DB_NAME = "shelf.db"

        @Volatile
        private var INSTANCE: ShelfDatabase? = null

        fun getInstance(context: Context): ShelfDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): ShelfDatabase {
            val holder = DbHolder()
            val base = Room.databaseBuilder(
                context.applicationContext,
                ShelfDatabase::class.java,
                DB_NAME
            )
                .fallbackToDestructiveMigration()
            val db = runCatching {
                base
                    .addCallback(SeedCallback { holder.db ?: error("DB not assigned during onCreate") })
                    .build()
            }.getOrElse { _: Throwable ->
                runCatching {
                    context.deleteDatabase(DB_NAME)
                }
                base.build()
            }
            holder.db = db
            return db
        }
    }
}

private class DbHolder { @Volatile var db: ShelfDatabase? = null }
