package com.jaeckel.urlvault.android.ai

import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LlamatikNativeBridge"

/**
 * `LlamaCppNativeBridge` backed by Llamatik (`com.llamatik:library`), a KMP
 * wrapper around llama.cpp that ships prebuilt JNI `.so` files.
 *
 * Llamatik exposes `LlamaBridge` as an `expect object` — i.e. a process-wide
 * singleton holding a single loaded model. This bridge serializes access with
 * a [Mutex] and tracks [currentPath] so that calls to [load] with a different
 * file unload the previous model first. Per-provider `loaded` flags in
 * [LlamaCppModelProvider] are not authoritative; the source of truth is here.
 */
class LlamatikNativeBridge : LlamaCppNativeBridge {

    private val mutex = Mutex()
    private var currentPath: String? = null

    private val available: Boolean by lazy {
        try {
            LlamaBridge.getModelPath("probe")
            Log.i(TAG, "Llamatik native runtime loaded (libllama_jni.so OK)")
            true
        } catch (t: Throwable) {
            // Full stack so we can see whether it's UnsatisfiedLinkError (so missing /
            // ABI mismatch / unresolved symbols), ExceptionInInitializerError (init
            // block threw — typically the System.loadLibrary call), or something else.
            Log.e(TAG, "Llamatik native runtime unavailable", t)
            false
        }
    }

    override fun isAvailable(): Boolean = available

    override suspend fun load(absolutePath: String) {
        mutex.withLock {
            if (currentPath == absolutePath) {
                Log.v(TAG, "load: already loaded $absolutePath, no-op")
                return
            }
            withContext(Dispatchers.IO) {
                if (currentPath != null) {
                    Log.i(TAG, "load: switching model — shutting down $currentPath first")
                    runCatching { LlamaBridge.shutdown() }
                    currentPath = null
                }
                Log.i(TAG, "load: initGenerateModel($absolutePath)")
                val t0 = System.currentTimeMillis()
                applyDefaultParams(maxTokens = DEFAULT_MAX_TOKENS)
                val ok = try {
                    LlamaBridge.initGenerateModel(absolutePath)
                } catch (t: Throwable) {
                    Log.e(TAG, "load: initGenerateModel threw", t)
                    throw t
                }
                if (!ok) {
                    Log.e(TAG, "load: initGenerateModel returned false for $absolutePath")
                    error("LlamaBridge.initGenerateModel returned false for $absolutePath")
                }
                currentPath = absolutePath
                Log.i(TAG, "load: ready in ${System.currentTimeMillis() - t0}ms — $absolutePath")
            }
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String =
        mutex.withLock {
            applyDefaultParams(maxTokens = maxTokens)
            runStream(prompt, timeoutMs = timeoutFor(maxTokens))
        }

    private fun timeoutFor(maxTokens: Int): Long =
        (maxTokens * MS_PER_TOKEN_ESTIMATE)
            .coerceAtLeast(MIN_TIMEOUT_MS)
            .coerceAtMost(MAX_TIMEOUT_MS)

    /**
     * Llamatik's binary ignores `updateGenerateParams.maxTokens` — it
     * generates up to its internal ceiling (~2048) regardless. EOT detection
     * helps when the model emits a recognized end token, but LFM2-style
     * models often don't, so we enforce a wallclock cap by calling
     * `nativeCancelGenerate` from a watchdog coroutine. The streaming entry
     * resets the cancel flag at start; the blocking `generate` does not, so
     * we'd self-block on the next call after a watchdog fire.
     */
    private suspend fun runStream(
        prompt: String,
        timeoutMs: Long,
    ): String = coroutineScope {
        val builder = StringBuilder()
        var error: Throwable? = null
        val t0 = System.currentTimeMillis()

        val watchdog = launch(Dispatchers.Default) {
            delay(timeoutMs)
            Log.w(TAG, "watchdog: cancelling generation after ${timeoutMs}ms")
            LlamaBridge.nativeCancelGenerate()
        }
        val callback = object : GenStream {
            override fun onDelta(text: String) { builder.append(text) }
            override fun onComplete() {}
            override fun onError(message: String) {
                error = RuntimeException(message)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                LlamaBridge.generateStream(prompt, callback)
            }
        } finally {
            watchdog.cancel()
        }
        error?.let { throw it }
        builder.toString().also {
            Log.i(
                TAG,
                "runStream: produced ${it.length} chars in " +
                    "${System.currentTimeMillis() - t0}ms (cap=${timeoutMs}ms)",
            )
        }
    }

    override suspend fun unload() {
        mutex.withLock {
            if (currentPath != null) {
                withContext(Dispatchers.IO) { LlamaBridge.shutdown() }
                currentPath = null
            }
        }
    }

    private fun applyDefaultParams(maxTokens: Int) {
        // Half the cores leaves headroom for the UI/system; clamping to >=1
        // covers exotic single-core targets. On a 4-big/4-little phone this
        // gives 4, matching the previous hardcoded value; on an 8-core
        // chiplet it scales up to 4 instead of starving big cores.
        val threads = maxOf(1, Runtime.getRuntime().availableProcessors() / 2)
        Log.i(TAG, "applyDefaultParams: maxTokens=$maxTokens contextLength=2048 temp=0.7 threads=$threads")
        LlamaBridge.updateGenerateParams(
            temperature = 0.7f,
            maxTokens = maxTokens,
            topP = 0.95f,
            topK = 40,
            repeatPenalty = 1.1f,
            contextLength = 2048,
            numThreads = threads,
            useMmap = true,
            flashAttention = false,
            batchSize = 512,
        )
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 128
        // Measured on a mid-range arm64-v8a device (Snapdragon 8 Gen 2 class)
        // running LFM2 1.2B Q4_K_M in llama.cpp. Slower devices may run at
        // 150–250 ms/token; adjust upward if timeouts are seen in practice.
        // The clamp to [MIN, MAX] means this only matters for very large token
        // budgets where proportional scaling would otherwise blow past 45 s.
        const val MS_PER_TOKEN_ESTIMATE = 400L
        const val MIN_TIMEOUT_MS = 12_000L
        const val MAX_TIMEOUT_MS = 45_000L
    }
}
