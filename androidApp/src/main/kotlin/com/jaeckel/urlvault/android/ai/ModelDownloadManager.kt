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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ModelDownloadManager"
private const val BUFFER_BYTES = 64 * 1024
private const val PROGRESS_EMIT_INTERVAL_MS = 250L

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

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private fun modelsDir(): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply {
            if (!exists()) mkdirs()
        }

    fun fileFor(entry: ModelCatalogEntry): File = File(modelsDir(), entry.localFileName())

    /**
     * Sidecar marker. Written only after the streaming write completes
     * successfully — its presence is the signal that the bytes on disk are
     * the *whole* model and not a half-finished download. A partial file by
     * itself (no marker) is left on disk so the next download() call can
     * resume via HTTP Range from where it stopped.
     */
    private fun completeMarkerFor(entry: ModelCatalogEntry): File =
        File(modelsDir(), entry.localFileName() + ".complete")

    /**
     * Scan the models directory at app start and re-register any GGUFs that
     * already match catalog entries. Keeps users from re-downloading after
     * a process restart. Only files with a `.complete` sidecar are treated
     * as Ready — bare partial files remain on disk for resume.
     */
    fun rehydrateFromDisk(catalog: List<ModelCatalogEntry>) {
        catalog.forEach { entry ->
            if (entry.runtime != ModelRuntime.LLAMA_CPP && entry.runtime != ModelRuntime.LEAP && entry.runtime != ModelRuntime.MEDIAPIPE) return@forEach
            val file = fileFor(entry)
            val marker = completeMarkerFor(entry)
            if (file.exists() && file.length() > 0 && marker.exists()) {
                _states.update { it + (entry.id to ModelDownloadState.Ready(file.absolutePath, file.length())) }
                registerProvider(entry, file)
            } else if (file.exists() && file.length() > 0) {
                // Partial download survived a process kill — surface bytes-so-far
                // so the UI shows progress and the user can hit Download to resume.
                Log.i(
                    TAG,
                    "rehydrate: ${entry.id} partial (${file.length()} of ~${entry.approxBytes} bytes); awaiting resume",
                )
                _states.update {
                    it + (entry.id to ModelDownloadState.Downloading(file.length(), entry.approxBytes))
                }
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
                val marker = completeMarkerFor(entry)
                // Stale marker from a delete-then-redownload could falsely
                // claim completion if the new download fails mid-way. Drop
                // it before we start writing.
                if (marker.exists()) marker.delete()
                downloadStreaming(entry, file)
                _states.update { it + (entry.id to ModelDownloadState.Verifying) }
                if (!file.exists() || file.length() == 0L) {
                    error("Downloaded file is empty")
                }
                // Touch the marker only after the streaming write returned
                // normally. A process kill mid-download leaves the bytes on
                // disk but no marker, so rehydrate sees a partial.
                marker.createNewFile()
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
        val marker = completeMarkerFor(entry)
        if (file.exists()) file.delete()
        if (marker.exists()) marker.delete()
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
            var existing = if (target.exists()) target.length() else 0L
            val token = authTokenProvider()

            // If a partial is already on disk, ask the server for the total
            // size first. Two pre-resume recovery paths:
            //   - existing == total: file is actually complete (typical for
            //     downloads that finished before the .complete marker was
            //     introduced — rehydrate sees no marker and bumps it to
            //     Downloading, then a Range: bytes=<total>- request 416s).
            //     Skip the HTTP fetch entirely; the caller will write the
            //     marker and register the provider.
            //   - existing > total: stale or corrupt partial. Truncate and
            //     fall through to a fresh download.
            if (existing > 0L) {
                val total = runCatching { discoverTotalBytes(entry.downloadUrl(), token) }
                    .getOrDefault(-1L)
                if (total > 0L && existing == total) {
                    Log.i(TAG, "${entry.id} already complete on disk ($existing bytes) — skipping HTTP fetch")
                    return@coroutineScope
                }
                if (total > 0L && existing > total) {
                    Log.w(TAG, "${entry.id} partial > server total ($existing > $total) — truncating")
                    // RandomAccessFile.setLength is the cleanest way to
                    // shrink a file in-place without losing the inode.
                    RandomAccessFile(target, "rw").use { it.setLength(0L) }
                    existing = 0L
                }
            }

            // HuggingFace serves a 302 to a CDN; we follow it manually so we keep
            // control of the Range header on the redirected request.
            val (connection, contentLength, isPartial) = openWithRedirects(
                urlString = entry.downloadUrl(),
                rangeStart = existing,
                token = token,
            )

            // BufferedInputStream.read() is a blocking JVM call — coroutine
            // cancellation alone doesn't interrupt it, so a user "Cancel" can
            // sit for up to readTimeout (60s) before the loop notices. Hook
            // the current Job so cancellation immediately disconnects the
            // socket; the in-flight read() then throws IOException and the
            // finally block below tidies up.
            coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) runCatching { connection.disconnect() }
            }

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
                        // Throttle UI flow emissions: a 64 KB buffer at typical
                        // CDN throughput fires several thousand times per
                        // second, which would thrash Compose recomposition for
                        // multi-hundred-MB models. 250 ms is fast enough to
                        // feel live but cheap enough to be inaudible.
                        var lastEmitMs = 0L
                        while (true) {
                            // Throws CancellationException on cancel — the
                            // earlier `while (isActive)` form let a cancelled
                            // coroutine exit the loop cleanly, which then
                            // looked indistinguishable from a normal EOF and
                            // caused the caller to write `.complete` over a
                            // partial file.
                            ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                _states.update {
                                    it + (entry.id to ModelDownloadState.Downloading(written, totalBytes))
                                }
                                lastEmitMs = now
                            }
                        }
                        // Final flush so the bar always lands on the true
                        // byte count rather than wherever the throttle last
                        // fired.
                        _states.update {
                            it + (entry.id to ModelDownloadState.Downloading(written, totalBytes))
                        }
                    }
                    // Premature-EOF guard: if the server told us the total via
                    // Content-Length / Content-Range and we wrote fewer bytes,
                    // the connection was cut short (CDN reset, network drop)
                    // and the file on disk is partial. Throw so the caller's
                    // catch leaves the bytes in place for resume but doesn't
                    // create the `.complete` marker. When `contentLength` was
                    // unreported we fall back to `entry.approxBytes`, which is
                    // only approximate — skip the strict check in that case.
                    if (contentLength > 0 && written != totalBytes) {
                        error("Download truncated: wrote $written of $totalBytes bytes")
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
     * Probe the server for the file's total size without downloading the
     * whole thing. Sends `Range: bytes=0-0` (a 1-byte slice) so the response
     * carries a `Content-Range: bytes 0-0/<total>` header we can parse.
     * Follows the same manual-redirect chain as openWithRedirects to keep
     * the Authorization header attached on CDN redirects. Returns -1 if the
     * server doesn't report a total (e.g. on a non-Range-capable origin).
     */
    private fun discoverTotalBytes(urlString: String, token: String?, maxHops: Int = 5): Long {
        var url = URL(urlString)
        var hops = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "URLVault/1.0")
                setRequestProperty("Range", "bytes=0-0")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val code = conn.responseCode
                when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        // "bytes 0-0/123456789"
                        val cr = conn.getHeaderField("Content-Range") ?: return -1L
                        return cr.substringAfterLast('/').toLongOrNull() ?: -1L
                    }
                    in 200..299 -> {
                        // Server ignored Range and returned the whole file —
                        // Content-Length is the total.
                        return conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                    }
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307, 308 -> {
                        val location = conn.getHeaderField("Location") ?: return -1L
                        url = URL(url, location)
                        hops++
                        if (hops > maxHops) return -1L
                    }
                    else -> return -1L
                }
            } finally {
                conn.disconnect()
            }
        }
    }

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
