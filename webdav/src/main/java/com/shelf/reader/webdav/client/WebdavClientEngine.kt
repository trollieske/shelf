package com.shelf.reader.webdav.client

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG_WEBDAV_ENGINE = "WebdavClientEngine"

enum class WebdavEntryType { FILE, FOLDER, UNKNOWN }

data class WebdavEntry(
    val name: String,
    val path: String,
    val href: String,
    val type: WebdavEntryType,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
    val etag: String? = null,
    val contentType: String? = null
)

class WebdavClientEngine {

    private var client: OkHttpClient? = null
    private var baseUrl: String? = null
    private var authHeader: String? = null
    private var connected = false

    val isConnected: Boolean
        get() = connected

    suspend fun connect(
        baseUrl: String,
        username: String,
        password: String? = null,
        bearerToken: String? = null,
        authType: String = "BASIC",
        trustAllCertificates: Boolean = false,
        userAgent: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val trustAll = trustAllCertificates
            val builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)

            if (userAgent != null) {
                builder.addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                }
            }

            if (trustAll) {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        Log.w(TAG_WEBDAV_ENGINE, "Trust-all: godtar klientsertifikat ${chain?.size ?: 0} stk (authType=$authType). Ikke anbefalt i produksjon.")
                    }
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        val subject = chain?.firstOrNull()?.subjectX500Principal?.name?.take(120) ?: "ukjent"
                        Log.w(TAG_WEBDAV_ENGINE, "Trust-all: godtar tjener-sertifikat: $subject (authType=$authType). Ikke anbefalt i produksjon — aktivert av bruker.")
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
            }

            client = builder.build()

            val normalizedBase = baseUrl.trimEnd('/')
            this@WebdavClientEngine.baseUrl = normalizedBase

            authHeader = when (authType.uppercase()) {
                "BEARER", "OAUTH2" -> "Bearer ${bearerToken ?: password ?: ""}"
                "NONE" -> null
                else -> Credentials.basic(username, password ?: "")
            }

            val probeUrl = normalizedBase + "/"
            val request = Request.Builder().url(probeUrl).method("PROPFIND", null)
                .header("Depth", "0")
                .header("Content-Type", "application/xml")
                .apply { if (authHeader != null) header("Authorization", authHeader!!) }
                .build()

            val response = client!!.newCall(request).execute()
            val success = response.isSuccessful || response.code == 404
            response.close()
            connected = success
            success
        } catch (_: Exception) {
            connected = false
            false
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        connected = false
        authHeader = null
        baseUrl = null
        client = null
    }

    private fun buildHref(path: String): String {
        val base = baseUrl?.trimEnd('/') ?: return ""
        val clean = if (path.startsWith("/")) path else "/$path"
        return "$base$clean"
    }

    private fun normalizePath(href: String): String {
        val base = baseUrl?.trimEnd('/') ?: return href
        val withoutBase = href.removePrefix(base)
        val decoded = Uri.decode(withoutBase).trimEnd('/')
        return decoded.ifBlank { "/" }
    }

    suspend fun listDirectory(path: String): List<WebdavEntry> = withContext(Dispatchers.IO) {
        val httpClient = client ?: return@withContext emptyList()
        try {
            val href = buildHref(path) + (if (!path.endsWith("/")) "/" else "")
            val bodyXml = """<?xml version="1.0"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                    <d:getcontentlength/>
                    <d:getlastmodified/>
                    <d:getcontenttype/>
                    <d:resourcetype/>
                    <d:getetag/>
                  </d:prop>
                </d:propfind>""".trimIndent().toRequestBody("application/xml".toMediaTypeOrNull())

            val request = Request.Builder().url(href).method("PROPFIND", bodyXml)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .apply { if (authHeader != null) header("Authorization", authHeader!!) }
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyText = response.body?.string().orEmpty()
            response.close()
            if (!response.isSuccessful) return@withContext emptyList()

            parseMultiStatus(bodyText, path)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMultiStatus(xml: String, requestPath: String): List<WebdavEntry> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val entries = mutableListOf<WebdavEntry>()
        var eventType = parser.eventType
        var inResponse = false
        var inProp = false
        var currentHref: String? = null
        var currentName: String? = null
        var isCollection = false
        var size: Long = 0L
        var modified: Long = 0L
        var etag: String? = null
        var contentType: String? = null

        val targetPath = requestPath.trimEnd('/').ifBlank { "/" }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val localName = parser.name?.substringAfterLast(':') ?: ""
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (localName.lowercase()) {
                        "response" -> {
                            inResponse = true
                            currentHref = null; currentName = null
                            isCollection = false; size = 0L; modified = 0L
                            etag = null; contentType = null
                        }
                        "propstat" -> {}
                        "prop" -> inProp = true
                        "href" -> if (inResponse && !inProp) currentHref = parser.nextText().trim()
                        "displayname" -> if (inProp) currentName = parser.nextText().trim()
                        "getcontentlength" -> if (inProp) size = parser.nextText().trim().toLongOrNull() ?: 0L
                        "getlastmodified" -> if (inProp) {
                            modified = parseHttpDate(parser.nextText().trim())
                        }
                        "getetag" -> if (inProp) etag = parser.nextText().trim().removeSurrounding("\"")
                        "getcontenttype" -> if (inProp) contentType = parser.nextText().trim()
                        "collection" -> if (inProp) isCollection = true
                        "resourcetype" -> {}
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (localName.lowercase()) {
                        "prop" -> inProp = false
                        "response" -> {
                            inResponse = false
                            val href = currentHref ?: ""
                            val normalized = normalizePath(href)
                            if (normalized != targetPath && normalized.isNotBlank()) {
                                val name = currentName ?: normalized.substringAfterLast('/').ifEmpty { normalized }
                                entries.add(
                                    WebdavEntry(
                                        name = name,
                                        path = normalized,
                                        href = href,
                                        type = if (isCollection) WebdavEntryType.FOLDER else WebdavEntryType.FILE,
                                        sizeBytes = size,
                                        modifiedEpochSec = modified,
                                        etag = etag,
                                        contentType = contentType
                                    )
                                )
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return entries.sortedWith(compareBy<WebdavEntry> { it.type != WebdavEntryType.FOLDER }.thenBy { it.name.lowercase() })
    }

    private fun parseHttpDate(s: String): Long {
        return runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).parse(s)?.time?.div(1000L) ?: 0L
        }.getOrElse { 0L }
    }

    suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Long = withContext(Dispatchers.IO) {
        val httpClient = client ?: return@withContext -1L
        try {
            localFile.parentFile?.mkdirs()
            val url = buildHref(remotePath)
            val request = Request.Builder().url(url).get()
                .apply { if (authHeader != null) header("Authorization", authHeader!!) }
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return@withContext -1L }
            val body = response.body ?: return@withContext -1L
            val total = body.contentLength()
            val stream = body.byteStream()
            FileOutputStream(localFile).use { out ->
                val buf = ByteArray(8192 * 8)
                var read: Int
                var downloaded = 0L
                while (stream.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
                downloaded
            }
        } catch (_: Exception) {
            -1L
        }
    }

    fun matchesFormat(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("epub", "pdf", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "txt", "html", "rtf", "md",
            "m4b", "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav", "zip")
    }
}
