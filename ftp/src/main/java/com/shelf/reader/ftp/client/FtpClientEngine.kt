package com.shelf.reader.ftp.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.FileSystemFile
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

enum class FtpEntryType { FILE, FOLDER, LINK, UNKNOWN }

data class FtpEntry(
    val name: String,
    val path: String,
    val type: FtpEntryType,
    val sizeBytes: Long,
    val modifiedEpochSec: Long
)

class FtpClientEngine {

    private var ftpClient: FTPClient? = null
    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var activeProtocol: FtpProtocol = FtpProtocol.FTP

    val isConnected: Boolean
        get() = (ftpClient?.isConnected == true) || (sshClient?.isConnected == true && sftpClient != null)

    suspend fun connect(
        server: String,
        port: Int,
        user: String,
        pass: String,
        protocol: FtpProtocol,
        usePassiveMode: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        disconnect() // Ensure fresh state
        activeProtocol = protocol

        return@withContext if (protocol == FtpProtocol.SFTP) {
            connectSftp(server, port, user, pass)
        } else {
            connectFtp(server, port, user, pass, protocol, usePassiveMode)
        }
    }

    private fun connectFtp(
        server: String,
        port: Int,
        user: String,
        pass: String,
        protocol: FtpProtocol,
        usePassiveMode: Boolean
    ): Boolean {
        try {
            val isImplicit = (protocol == FtpProtocol.FTPS_IMPLICIT)
            val isExplicit = (protocol == FtpProtocol.FTPS_EXPLICIT)

            val client: FTPClient = if (isImplicit || isExplicit) {
                FTPSClient(isImplicit).apply {
                    trustManager = TrustManagerUtils.getAcceptAllTrustManager()
                }
            } else {
                FTPClient()
            }

            client.setConnectTimeout(15000)
            client.defaultTimeout = 15000
            client.setDataTimeout(java.time.Duration.ofMillis(15000L))
            client.controlEncoding = "UTF-8"
            client.bufferSize = 1024 * 1024 // 1 MB buffer

            ftpClient = client
            client.connect(server, port)

            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                Log.e("FtpClientEngine", "FTP connect refused: replyCode=$reply")
                client.disconnect()
                ftpClient = null
                return false
            }

            val loginOk = client.login(user, pass)
            if (!loginOk) {
                Log.e("FtpClientEngine", "FTP login failed for user '$user'")
                client.disconnect()
                ftpClient = null
                return false
            }

            // CRITICAL FTPS FIX: Secure the data channel with PROT P (Private) & PBSZ 0
            if (client is FTPSClient) {
                try {
                    client.execPBSZ(0)
                    client.execPROT("P")
                } catch (e: Exception) {
                    Log.w("FtpClientEngine", "execPBSZ/execPROT failed: ${e.message}")
                }
            }

            if (usePassiveMode) {
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }
            try {
                client.setPassiveNatWorkaroundStrategy(null)
            } catch (_: Exception) {}

            client.setFileType(FTP.BINARY_FILE_TYPE)
            return true
        } catch (e: Exception) {
            Log.e("FtpClientEngine", "FTP connect failed: ${e.message}", e)
            disconnectInternal()
            return false
        }
    }

    private fun connectSftp(
        server: String,
        port: Int,
        user: String,
        pass: String
    ): Boolean {
        try {
            val ssh = SSHClient()
            ssh.connectTimeout = 15000
            ssh.timeout = 15000
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            
            ssh.connect(server, port)
            ssh.authPassword(user, pass)

            if (!ssh.isAuthenticated) {
                Log.e("FtpClientEngine", "SFTP auth failed for user '$user'")
                ssh.disconnect()
                return false
            }

            val sftp = ssh.newSFTPClient()
            sshClient = ssh
            sftpClient = sftp
            return true
        } catch (e: Exception) {
            Log.e("FtpClientEngine", "SFTP connect failed: ${e.message}", e)
            disconnectInternal()
            return false
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        try {
            sftpClient?.close()
        } catch (_: Exception) {}
        try {
            sshClient?.disconnect()
        } catch (_: Exception) {}
        try {
            ftpClient?.let {
                if (it.isConnected) {
                    it.logout()
                    it.disconnect()
                }
            }
        } catch (_: Exception) {}

        sftpClient = null
        sshClient = null
        ftpClient = null
    }

    suspend fun listDirectory(path: String): List<FtpEntry> = withContext(Dispatchers.IO) {
        val targetPath = if (path.isBlank()) "/" else path
        if (activeProtocol == FtpProtocol.SFTP) {
            listDirectorySftp(targetPath)
        } else {
            listDirectoryFtp(targetPath)
        }
    }

    suspend fun listDirectoryRecursive(
        startPath: String,
        maxDepth: Int = 4
    ): List<FtpEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FtpEntry>()
        val visited = mutableSetOf<String>()

        suspend fun crawl(currentPath: String, currentDepth: Int) {
            if (currentDepth > maxDepth) return
            val normalizedPath = if (currentPath.isBlank()) "/" else currentPath
            if (!visited.add(normalizedPath)) return

            try {
                val entries = listDirectory(normalizedPath)
                for (entry in entries) {
                    if (entry.type == FtpEntryType.FILE) {
                        result.add(entry)
                    } else if (entry.type == FtpEntryType.FOLDER && currentDepth < maxDepth) {
                        crawl(entry.path, currentDepth + 1)
                    }
                }
            } catch (e: Exception) {
                Log.w("FtpClientEngine", "Crawl error at path $currentPath: ${e.message}")
            }
        }

        crawl(startPath, 1)
        return@withContext result
    }

    private fun listDirectoryFtp(path: String): List<FtpEntry> {
        val client = ftpClient ?: throw IOException("Ikke tilkoblet FTP-server")
        try {
            val targetPath = if (path.isBlank()) "/" else path

            // 1. Try CWD first
            if (targetPath != "/") {
                try {
                    client.changeWorkingDirectory(targetPath)
                } catch (e: Exception) {
                    Log.w("FtpClientEngine", "CWD to '$targetPath' failed: ${e.message}")
                }
            } else {
                try {
                    client.changeWorkingDirectory("/")
                } catch (_: Exception) {}
            }

            // 2. Fetch list with automatic Active Mode fallback if Passive Mode times out
            var files: Array<FTPFile>? = null
            try {
                client.setDataTimeout(java.time.Duration.ofMillis(10000L))
                files = client.listFiles()
            } catch (e: Exception) {
                Log.w("FtpClientEngine", "listFiles in current mode failed (${e.message}), trying Active Mode fallback...")
                client.enterLocalActiveMode()
                client.setDataTimeout(java.time.Duration.ofMillis(10000L))
                files = client.listFiles()
            }

            if (files == null || files.isEmpty()) {
                // Fallback attempt: try passing direct path
                try {
                    files = client.listFiles(targetPath)
                } catch (_: Exception) {}
            }

            if (files == null) {
                val reply = client.replyString ?: "Ingen respons"
                throw IOException("Server returnerte ingen filliste for '$targetPath'. Svar: $reply")
            }

            val basePath = when {
                targetPath == "/" || targetPath.isBlank() -> "/"
                targetPath.endsWith("/") -> targetPath
                else -> "$targetPath/"
            }

            val entries: List<FtpEntry> = files.mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null

                val type = when {
                    file.isDirectory -> FtpEntryType.FOLDER
                    file.isFile -> FtpEntryType.FILE
                    file.isSymbolicLink -> FtpEntryType.LINK
                    else -> FtpEntryType.UNKNOWN
                }

                val fullPath = if (basePath == "/") "/$name" else "$basePath$name"
                FtpEntry(
                    name = name,
                    path = fullPath,
                    type = type,
                    sizeBytes = file.size,
                    modifiedEpochSec = (file.timestamp?.time?.time ?: 0L) / 1000L
                )
            }

            return entries.sortedWith(compareBy<FtpEntry> { it.type != FtpEntryType.FOLDER }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            Log.e("FtpClientEngine", "listDirectoryFtp feilet for path: $path", e)
            throw e
        }
    }

    private fun listDirectorySftp(path: String): List<FtpEntry> {
        val sftp = sftpClient ?: throw IOException("Ikke tilkoblet SFTP-server")
        try {
            val resources: List<RemoteResourceInfo> = sftp.ls(path)
            val basePath = when {
                path == "/" || path.isBlank() -> "/"
                path.endsWith("/") -> path
                else -> "$path/"
            }

            val entries: List<FtpEntry> = resources.mapNotNull { res ->
                val name = res.name ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null

                val type = when (res.attributes.type) {
                    FileMode.Type.DIRECTORY -> FtpEntryType.FOLDER
                    FileMode.Type.REGULAR -> FtpEntryType.FILE
                    FileMode.Type.SYMLINK -> FtpEntryType.LINK
                    else -> FtpEntryType.UNKNOWN
                }

                val fullPath = if (basePath == "/") "/$name" else "$basePath$name"
                FtpEntry(
                    name = name,
                    path = fullPath,
                    type = type,
                    sizeBytes = res.attributes.size,
                    modifiedEpochSec = res.attributes.mtime
                )
            }

            return entries.sortedWith(compareBy<FtpEntry> { it.type != FtpEntryType.FOLDER }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            Log.e("FtpClientEngine", "listDirectorySftp feilet for path: $path", e)
            throw e
        }
    }

    suspend fun downloadFile(remotePath: String, localFile: File): Long = withContext(Dispatchers.IO) {
        localFile.parentFile?.mkdirs()
        if (activeProtocol == FtpProtocol.SFTP) {
            downloadFileSftp(remotePath, localFile)
        } else {
            downloadFileFtp(remotePath, localFile)
        }
    }

    private fun downloadFileFtp(remotePath: String, localFile: File): Long {
        val client = ftpClient ?: return -1L
        var fos: FileOutputStream? = null
        return try {
            fos = FileOutputStream(localFile)
            val ok = client.retrieveFile(remotePath, fos)
            fos.flush()
            if (ok && localFile.exists()) localFile.length() else -1L
        } catch (e: Exception) {
            Log.e("FtpClientEngine", "downloadFileFtp feilet: ${e.message}", e)
            -1L
        } finally {
            try { fos?.close() } catch (_: Exception) {}
        }
    }

    private fun downloadFileSftp(remotePath: String, localFile: File): Long {
        val sftp = sftpClient ?: return -1L
        return try {
            sftp.get(remotePath, FileSystemFile(localFile))
            if (localFile.exists()) localFile.length() else -1L
        } catch (e: Exception) {
            Log.e("FtpClientEngine", "downloadFileSftp feilet: ${e.message}", e)
            -1L
        }
    }

    suspend fun downloadFileToStream(remotePath: String, sink: OutputStream): Long = withContext(Dispatchers.IO) {
        if (activeProtocol == FtpProtocol.SFTP) {
            val sftp = sftpClient ?: return@withContext 0L
            try {
                val tempFile = File.createTempFile("sftp_dl_", ".tmp")
                sftp.get(remotePath, FileSystemFile(tempFile))
                val len = tempFile.length()
                tempFile.inputStream().use { input -> input.copyTo(sink) }
                tempFile.delete()
                len
            } catch (_: Exception) { 0L }
        } else {
            val client = ftpClient ?: return@withContext 0L
            try {
                val ok = client.retrieveFile(remotePath, sink)
                if (ok) 1L else 0L
            } catch (_: Exception) { 0L }
        }
    }
}
