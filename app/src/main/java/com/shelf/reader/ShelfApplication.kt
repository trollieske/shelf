package com.shelf.reader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.app.workers.MediaScannerWorker
import com.shelf.reader.ftp.worker.FtpSyncWorker
import kotlinx.coroutines.DelicateCoroutinesApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ShelfApplication : Application(), ImageLoaderFactory {

    private var _database: ShelfDatabase? = null
    val database: ShelfDatabase
        get() = _database ?: synchronized(this) {
            _database ?: runCatching { ShelfDatabase.getInstance(this) }
                .getOrElse {
                    _database = null
                    deleteDatabase("shelf.db")
                    ShelfDatabase.getInstance(this)
                }
                .also { _database = it }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Warm up DB on a background thread; do NOT block app onCreate.
        // First access will happen from LibraryScreen on main; getIfCreated would be ideal but
        // current API returns non-null; instead ensure DB path deleted if corrupted on prestart.
        val warmUpThread = Thread {
            runCatching { database }
        }
        warmUpThread.name = "shelf-db-warm"
        warmUpThread.isDaemon = true
        warmUpThread.start()

        // Future-proof background scanning & sync
        MediaScannerWorker.schedule(this)
        FtpSyncWorker.schedule(this)
        runCatching { com.shelf.reader.torrent.worker.TorrentDownloadWorker.schedule(this) }
        runCatching { com.shelf.reader.torrent.worker.TorrentDownloadWorker.runNow(this) }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .okHttpClient(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            )
            .components {
                add(SvgDecoder.Factory())
                add(GifDecoder.Factory())
            }
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        lateinit var instance: ShelfApplication
            private set
    }
}
