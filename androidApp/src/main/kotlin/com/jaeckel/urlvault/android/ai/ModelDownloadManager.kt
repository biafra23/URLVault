package com.jaeckel.urlvault.android.ai

import android.content.Context
import android.util.Log
import com.jaeckel.urlvault.ai.LocalModelRegistry
import com.jaeckel.urlvault.ai.ModelCatalogEntry
import com.jaeckel.urlvault.ai.ModelDownloadState
import com.jaeckel.urlvault.ai.ModelRuntime
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloadManager"
private const val BUFFER_BYTES = 64 * 1024

/**
 * Downloads catalog models from Hugging Face directly into the app's external
 * files directory. Uses [HttpURLConnection] for byte-level streaming control
 * (the shared Ktor `HttpClient` has a 10s request timeout configured for HTML
 * scraping and isn't suitable for multi-hundred-MB downloads).
 *
 * On `Ready`, instantiates a `LlamaCppModelProvider` and registers it in the
 * `LocalModelRegistry`. On delete or failed download, deregisters and removes
 * the file.
 */
class ModelDownloadManager(
    private val context: Context,
    private val sharedHttp: HttpClient,
    private val registry: LocalModelRegistry,
    private val bridge: LlamaCppNativeBridge,
    private val leapBridge: LeapNativeBridge,
    private val liteRtLmBridge: LiteRtLmNativeBridge,
    private val appScope: CoroutineScope,
    private val authTokenProvider: () -> String? = { null },
) {

    private val _states = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()

    private fun modelsDir(): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply {
            if (!exists()) mkdirs()
        }

    fun fileFor(entry: ModelCatalogEntry): File = File(modelsDir(), entry.localFileName())

    /**
     * Scan the models directory at app start and re-register any GGUFs that
     * already match catalog entries. Keeps users from re-downloading after
     * a process restart.
     */
    fun rehydrateFromDisk(catalog: List<ModelCatalogEntry>) {
        catalog.forEach { entry ->
            if (entry.runtime != ModelRuntime.LLAMA_CPP && entry.runtime != ModelRuntime.LEAP && entry.runtime != ModelRuntime.MEDIAPIPE) return@forEach
            val file = fileFor(entry)
            if (file.exists() && file.length() > 0) {
                _states.update { it + (entry.id to ModelDownloadState.Ready(file.absolutePath, file.length())) }
                registerProvider(entry, file)
            }
        }
    }

    fun download(entry: ModelCatalogEntry) {
        if (activeJobs[entry.id]?.isActive == true) {
            Log.d(TAG, "Download already in progress for ${entry.id}")
            return
        }
        if (entry.runtime != ModelRuntime.LLAMA_CPP && entry.runtime != ModelRuntime.LEAP && entry.runtime != ModelRuntime.MEDIAPIPE) {
            _states.update {
                it + (entry.id to ModelDownloadState.Failed("Runtime ${entry.runtime} not yet supported"))
            }
            return
        }

        val job = appScope.launch(Dispatchers.IO) {
            _states.update { it + (entry.id to ModelDownloadState.Queued) }
            try {
                val file = fileFor(entry)
                downloadStreaming(entry, file)
                _states.update { it + (entry.id to ModelDownloadState.Verifying) }
                if (!file.exists() || file.length() == 0L) {
                    error("Downloaded file is empty")
                }
                _states.update { it + (entry.id to ModelDownloadState.Ready(file.absolutePath, file.length())) }
                registerProvider(entry, file)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${entry.id}: ${e.message}", e)
                _states.update { it + (entry.id to ModelDownloadState.Failed(e.message ?: "Unknown error")) }
            } finally {
                activeJobs.remove(entry.id)
            }
        }
        activeJobs[entry.id] = job
    }

    fun cancel(entry: ModelCatalogEntry) {
        activeJobs.remove(entry.id)?.cancel()
        _states.update { it + (entry.id to ModelDownloadState.Idle) }
    }

    fun delete(entry: ModelCatalogEntry) {
        cancel(entry)
        val file = fileFor(entry)
        if (file.exists()) file.delete()
        registry.unregister(entry.id)
        _states.update { it + (entry.id to ModelDownloadState.Idle) }
    }

    /**
     * Streamed download with HTTP Range resume. Sends `Range: bytes=<existing>-`
     * if a partial file is already on disk; appends to it on `206 Partial Content`,
     * truncates and starts fresh on `200 OK`.
     */
    private suspend fun downloadStreaming(entry: ModelCatalogEntry, target: File) = withContext(Dispatchers.IO) {
        coroutineScope {
            val existing = if (target.exists()) target.length() else 0L
            val token = authTokenProvider()

            // HuggingFace serves a 302 to a CDN; we follow it manually so we keep
            // control of the Range header on the redirected request.
            val (connection, contentLength, isPartial) = openWithRedirects(
                urlString = entry.downloadUrl(),
                rangeStart = existing,
                token = token,
            )

            try {
                val totalBytes = if (isPartial && contentLength > 0) {
                    existing + contentLength
                } else if (contentLength > 0) {
                    contentLength
                } else {
                    entry.approxBytes
                }

                val raf = RandomAccessFile(target, "rw")
                try {
                    if (isPartial) raf.seek(existing) else raf.setLength(0)
                    var written = if (isPartial) existing else 0L

                    BufferedInputStream(connection.inputStream).use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            written += read
                            _states.update {
                                it + (entry.id to ModelDownloadState.Downloading(written, totalBytes))
                            }
                        }
                    }
                } finally {
                    raf.close()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private data class OpenResult(
        val connection: HttpURLConnection,
        val contentLength: Long,
        val isPartial: Boolean,
    )

    /**
     * Follow up to 5 redirects manually so we re-apply the Authorization /
     * Range headers on each hop (HttpURLConnection's automatic redirect
     * stripping would otherwise drop them).
     */
    private fun openWithRedirects(
        urlString: String,
        rangeStart: Long,
        token: String?,
        maxHops: Int = 5,
    ): OpenResult {
        var url = URL(urlString)
        var hops = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "URLVault/1.0")
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            when (code) {
                in 200..299 -> {
                    val length = conn.contentLengthLong
                    return OpenResult(conn, length, code == HttpURLConnection.HTTP_PARTIAL)
                }
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val location = conn.getHeaderField("Location")
                        ?: error("HTTP $code redirect without Location header")
                    conn.disconnect()
                    url = URL(url, location)
                    hops++
                    if (hops > maxHops) error("Too many redirects")
                }
                else -> {
                    conn.disconnect()
                    error("HTTP $code from $urlString")
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun registerProvider(entry: ModelCatalogEntry, file: File) {
        val provider = when (entry.runtime) {
            ModelRuntime.LLAMA_CPP -> LlamaCppModelProvider(
                id = entry.id,
                displayName = entry.displayName,
                modelFile = file.absolutePath,
                bridge = bridge,
                httpClient = sharedHttp,
            )
            ModelRuntime.LEAP -> LeapModelProvider(
                id = entry.id,
                displayName = entry.displayName,
                modelFile = file.absolutePath,
                bridge = leapBridge,
                httpClient = sharedHttp,
            )
            ModelRuntime.MEDIAPIPE -> LiteRtLmModelProvider(
                id = entry.id,
                displayName = entry.displayName,
                modelFile = file.absolutePath,
                bridge = liteRtLmBridge,
                httpClient = sharedHttp,
            )
            else -> {
                Log.d(TAG, "registerProvider: skip ${entry.id} (runtime=${entry.runtime})")
                return
            }
        }
        registry.register(provider)
        Log.i(
            TAG,
            "registerProvider: ${entry.id} -> ${file.absolutePath} (${file.length()} bytes, runtime=${entry.runtime})",
        )
    }
}
