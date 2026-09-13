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
        HandoffLinkEntity::class,
        ReadingSessionEntity::class,
        DailyReadingEntity::class,
        ReadingProfileEntity::class,
        PodcastFeedEntity::class,
        PodcastEpisodeEntity::class,
        PodcastPlaybackEntity::class,
        PodcastDownloadEntity::class
    ],
    version = 7,
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
    abstract fun readingRhythmDao(): com.shelf.reader.data.local.dao.ReadingRhythmDao
    abstract fun podcastFeedDao(): com.shelf.reader.data.local.dao.PodcastFeedDao
    abstract fun podcastEpisodeDao(): com.shelf.reader.data.local.dao.PodcastEpisodeDao
    abstract fun podcastPlaybackDao(): com.shelf.reader.data.local.dao.PodcastPlaybackDao
    abstract fun podcastDownloadDao(): com.shelf.reader.data.local.dao.PodcastDownloadDao

    companion object {
        private const val DB_NAME = "shelf.db"

        /**
         * v6 -> v7: adds the podcast tables. Purely additive; no ebook/audiobook table is
         * touched. Podcast playback and downloads remain separate from book progress.
         */
        val MIGRATION_6_7: androidx.room.migration.Migration = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_feeds` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`feed_url` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
                        "`description` TEXT, `artwork_url` TEXT, `website_url` TEXT, `language` TEXT, " +
                        "`categories_json` TEXT, `explicit` INTEGER, `is_followed` INTEGER NOT NULL, " +
                        "`added_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`last_synced_at` INTEGER, `last_sync_status` TEXT, `last_sync_error` TEXT)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcast_feeds_feed_url` " +
                        "ON `podcast_feeds` (`feed_url`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_episodes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `feed_id` INTEGER NOT NULL, " +
                        "`stable_identity` TEXT NOT NULL, `guid` TEXT, `enclosure_url` TEXT NOT NULL, " +
                        "`enclosure_mime_type` TEXT, `enclosure_length_bytes` INTEGER, `title` TEXT NOT NULL, " +
                        "`description` TEXT, `artwork_url` TEXT, `published_at` INTEGER, `duration_ms` INTEGER, " +
                        "`season_number` INTEGER, `episode_number` INTEGER, `explicit` INTEGER, " +
                        "`episode_type` TEXT, `added_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`feed_id`) REFERENCES `podcast_feeds`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_episodes_feed_id` ON `podcast_episodes` (`feed_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_episodes_published_at` ON `podcast_episodes` (`published_at`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_podcast_episodes_stable_identity` ON `podcast_episodes` (`stable_identity`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_episodes_feed_id_published_at` ON `podcast_episodes` (`feed_id`, `published_at`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_playback` (" +
                        "`episode_id` INTEGER NOT NULL, `position_ms` INTEGER NOT NULL, `duration_ms` INTEGER, " +
                        "`last_played_at` INTEGER, `is_completed` INTEGER NOT NULL, `completed_at` INTEGER, " +
                        "`playback_speed` REAL NOT NULL, PRIMARY KEY(`episode_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_playback_last_played_at` ON `podcast_playback` (`last_played_at`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_downloads` (" +
                        "`episode_id` INTEGER NOT NULL, `download_manager_id` INTEGER, `local_uri` TEXT, " +
                        "`status` TEXT NOT NULL, `requested_at` INTEGER, `completed_at` INTEGER, " +
                        "`downloaded_bytes` INTEGER, `total_bytes` INTEGER, `failure_reason` TEXT, " +
                        "PRIMARY KEY(`episode_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_downloads_status` ON `podcast_downloads` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_podcast_downloads_episode_id` ON `podcast_downloads` (`episode_id`)")
            }
        }

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
                .addMigrations(MIGRATION_6_7)
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
