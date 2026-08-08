package com.shelf.reader.data.local.dao

import androidx.room.*
import com.shelf.reader.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FtpServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: FtpServerEntity): Long

    @Update
    suspend fun update(server: FtpServerEntity)

    @Delete
    suspend fun delete(server: FtpServerEntity)

    @Query("SELECT * FROM ftp_servers WHERE is_active = 1 ORDER BY position, display_name")
    fun observeAll(): Flow<List<FtpServerEntity>>

    @Query("SELECT * FROM ftp_servers WHERE is_active = 1 ORDER BY position, display_name")
    suspend fun getAll(): List<FtpServerEntity>

    @Query("SELECT * FROM ftp_servers WHERE id = :id")
    fun observeById(id: Long): Flow<FtpServerEntity?>

    @Query("SELECT * FROM ftp_servers WHERE id = :id")
    suspend fun getById(id: Long): FtpServerEntity?

    @Query("SELECT * FROM ftp_servers WHERE sync_enabled = 1 AND is_active = 1")
    suspend fun getSyncEnabledServers(): List<FtpServerEntity>

    @Query("UPDATE ftp_servers SET sync_last_check_at = :now WHERE id = :id")
    suspend fun markSynced(id: Long, now: Long = System.currentTimeMillis())
}

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Delete
    suspend fun delete(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks ORDER BY status IN ('RUNNING','PENDING') DESC, priority DESC, created_at DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status IN ('RUNNING','PENDING','PAUSED') ORDER BY priority DESC, created_at DESC")
    fun observeActive(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'PENDING' ORDER BY priority DESC, created_at ASC LIMIT 1")
    suspend fun getNextPending(): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'RUNNING' LIMIT 1")
    suspend fun getRunning(): DownloadTaskEntity?

    @Query("UPDATE download_tasks SET status = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: Long)

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('RUNNING','PENDING')")
    fun observeActiveCount(): Flow<Int>
}

@Dao
interface CachedPathDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(path: CachedPathEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(paths: List<CachedPathEntity>)

    @Query("SELECT * FROM cached_paths WHERE server_id = :serverId AND parent_path = :parentPath ORDER BY is_directory DESC, name ASC")
    fun observeByParent(serverId: Long, parentPath: String): Flow<List<CachedPathEntity>>

    @Query("SELECT * FROM cached_paths WHERE server_id = :serverId AND parent_path = :parentPath ORDER BY is_directory DESC, name ASC")
    suspend fun getByParent(serverId: Long, parentPath: String): List<CachedPathEntity>

    @Query("DELETE FROM cached_paths WHERE server_id = :serverId AND parent_path LIKE :path || '%'")
    suspend fun invalidateUnder(serverId: Long, path: String)
}

@Dao
interface SyncHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SyncHistoryEntity): Long

    @Update
    suspend fun update(history: SyncHistoryEntity)

    @Query("SELECT * FROM sync_history WHERE server_id = :serverId ORDER BY started_at DESC LIMIT :limit")
    fun observeForServer(serverId: Long, limit: Int = 20): Flow<List<SyncHistoryEntity>>

    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT 50")
    fun observeAll(): Flow<List<SyncHistoryEntity>>
}
