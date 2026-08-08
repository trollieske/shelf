package com.shelf.reader.ftp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import com.shelf.reader.ftp.client.FtpProtocol

data class FtpSavedServer(
    val id: Long,
    val name: String,
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: FtpProtocol = FtpProtocol.FTP,
    val usePassiveMode: Boolean = true,
    val defaultRemotePath: String = "/"
) {
    val useTls: Boolean
        get() = protocol.isSecure
}

class FtpServerStore(context: Context) {

    companion object {
        private const val FILE_NAME = "shelf_ftp_servers_enc.xml"
        private const val KEY_SERVERS = "servers_json"
    }

    private val appContext = context.applicationContext

    private val prefs by lazy {
        runCatching {
            val master = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            appContext.getSharedPreferences(
                "${appContext.packageName}_ftp_plain",
                Context.MODE_PRIVATE
            )
        }
    }

    private val _servers = MutableStateFlow<List<FtpSavedServer>>(emptyList())
    val servers: StateFlow<List<FtpSavedServer>> = _servers.asStateFlow()

    @Volatile private var loaded = false
    private val loadLock = Any()

    init {
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            try {
                val raw = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
                _servers.value = parse(raw)
            } catch (_: Throwable) {
                _servers.value = emptyList()
            }
            loaded = true
        }
    }

    fun save(server: FtpSavedServer): FtpSavedServer {
        ensureLoaded()
        val current = _servers.value.toMutableList()
        val finalServer = if (server.id == 0L) server.copy(id = (current.maxOfOrNull { it.id } ?: 0L) + 1) else server
        val existing = current.indexOfFirst { it.id == finalServer.id }
        if (existing >= 0) current[existing] = finalServer else current.add(finalServer)
        persist(current)
        return finalServer
    }

    fun delete(id: Long) {
        ensureLoaded()
        val current = _servers.value.filter { it.id != id }
        persist(current)
    }

    fun get(id: Long): FtpSavedServer? {
        ensureLoaded()
        return _servers.value.firstOrNull { it.id == id }
    }

    // ---------- internals ---------

    private fun persist(list: List<FtpSavedServer>) {
        runCatching {
            val json = JSONArray()
            list.forEach { s ->
                json.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("server", s.server)
                    put("port", s.port)
                    put("username", s.username)
                    put("password", s.password)
                    put("protocol", s.protocol.name)
                    put("useTls", s.protocol.isSecure)
                    put("usePassiveMode", s.usePassiveMode)
                    put("defaultRemotePath", s.defaultRemotePath)
                })
            }
            prefs.edit().putString(KEY_SERVERS, json.toString()).apply()
        }
        _servers.value = list
    }

    private fun parse(raw: String): List<FtpSavedServer> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val rawProto = o.optString("protocol", "")
            val legacyTls = o.optBoolean("useTls", false)
            val proto = when {
                rawProto.isNotBlank() -> runCatching { FtpProtocol.valueOf(rawProto) }.getOrDefault(FtpProtocol.FTP)
                legacyTls -> FtpProtocol.FTPS_EXPLICIT
                else -> FtpProtocol.FTP
            }
            FtpSavedServer(
                id = o.optLong("id", 0L),
                name = o.optString("name", ""),
                server = o.optString("server", ""),
                port = o.optInt("port", proto.defaultPort),
                username = o.optString("username", ""),
                password = o.optString("password", ""),
                protocol = proto,
                usePassiveMode = o.optBoolean("usePassiveMode", true),
                defaultRemotePath = o.optString("defaultRemotePath", "/")
            )
        }.filter { it.server.isNotBlank() }
    }.getOrElse { emptyList() }
}
