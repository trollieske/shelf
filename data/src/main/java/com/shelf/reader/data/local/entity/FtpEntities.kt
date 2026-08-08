package com.shelf.reader.data.local.entity

import androidx.room.*

enum class ProtocolEntity { FTP, FTPS, SFTP }
enum class FtpModeEntity { PASSIVE, ACTIVE }
enum class SyncIntervalEntity { MANUAL, MIN_15, HOUR_1, HOUR_6, DAILY, ON_APP_OPEN }
enum class ConnectionSecurityEntity { EXPLICIT_TLS, IMPLICIT_TLS, NONE }

@Entity(tableName = "ftp_servers")
data class FtpServerEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "protocol") val protocol: ProtocolEntity,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: String? = null,
    @ColumnInfo(name = "private_key_path") val privateKeyPath: String? = null,
    @ColumnInfo(name = "private_key_passphrase_encrypted") val privateKeyPassphraseEncrypted: String? = null,

    @ColumnInfo(name = "base_path") val basePath: String = "/",
    @ColumnInfo(name = "mode") val mode: FtpModeEntity = FtpModeEntity.PASSIVE,
    @ColumnInfo(name = "security") val security: ConnectionSecurityEntity = ConnectionSecurityEntity.NONE,

    @ColumnInfo(name = "encoding") val encoding: String = "UTF-8",
    @ColumnInfo(name = "timeout_seconds") val timeoutSeconds: Int = 30,

    @ColumnInfo(name = "color") val color: Int? = null,
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,

    @ColumnInfo(name = "sync_enabled") val syncEnabled: Boolean = false,
    @ColumnInfo(name = "sync_interval") val syncInterval: SyncIntervalEntity = SyncIntervalEntity.MANUAL,
    @ColumnInfo(name = "sync_paths_json") val syncPathsJson: String? = null,
    @ColumnInfo(name = "sync_wifi_only") val syncWifiOnly: Boolean = true,
    @ColumnInfo(name = "sync_include_pattern") val syncIncludePattern: String? = null,
    @ColumnInfo(name = "sync_exclude_pattern") val syncExcludePattern: String? = null,
    @ColumnInfo(name = "sync_last_check_at") val syncLastCheckAt: Long? = null,

    @ColumnInfo(name = "last_connected_at") val lastConnectedAt: Long? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(entity = FtpServerEntity::class, parentColumns = ["id"], childColumns = ["server_id"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("server_id")]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "remote_name") val remoteName: String,
    @ColumnInfo(name = "local_path") val localPath: String? = null,

    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long = 0L,

    @ColumnInfo(name = "status") val status: DownloadStatusEntity = DownloadStatusEntity.PENDING,
    @ColumnInfo(name = "priority") val priority: Int = 0,

    @ColumnInfo(name = "auto_import") val autoImport: Boolean = true,
    @ColumnInfo(name = "imported_book_id") val importedBookId: Long? = null,

    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null
)

enum class DownloadStatusEntity { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

@Entity(
    tableName = "cached_paths",
    foreignKeys = [
        ForeignKey(entity = FtpServerEntity::class, parentColumns = ["id"], childColumns = ["server_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("server_id", "path", unique = true)]
)
data class CachedPathEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "parent_path") val parentPath: String = "/",
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "is_directory") val isDirectory: Boolean,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "modified_time") val modifiedTime: Long = 0L,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_history")
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "files_found") val filesFound: Int = 0,
    @ColumnInfo(name = "files_new") val filesNew: Int = 0,
    @ColumnInfo(name = "files_downloaded") val filesDownloaded: Int = 0,
    @ColumnInfo(name = "files_failed") val filesFailed: Int = 0,
    @ColumnInfo(name = "status") val status: DownloadStatusEntity = DownloadStatusEntity.PENDING,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null
)
