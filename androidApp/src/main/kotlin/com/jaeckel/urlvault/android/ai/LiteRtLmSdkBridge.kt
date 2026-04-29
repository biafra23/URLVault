package com.jaeckel.urlvault.android.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "LiteRtLmSdkBridge"

/**
 * Strategy for ordering [Backend] candidates that [LiteRtLmSdkBridge.load]
 * tries during model initialisation. The strategy is invoked once per
 * `load()` with the JNI library directory; it returns labelled backends
 * in the order to attempt them. Tests inject a deterministic strategy;
 * production uses [DefaultBackendStrategy] (NPU → GPU → CPU).
 */
fun interface LiteRtLmBackendStrategy {
    fun candidates(nativeLibDir: String): List<Pair<String, Backend>>
}

/**
 * NPU first when the device's `nativeLibraryDir` is non-blank (vendor libs
 * are loaded from there for QCS / Pixel chips), then **CPU**, then GPU.
 *
 * GPU is intentionally deprioritised below CPU. The LiteRT-LM SDK
 * auto-selects the Top-K sampler based on engine backend: GPU engine
 * pulls in the WebGPU / OpenCL sampler chain, which on Pixel Tensor (7a /
 * 8 / 9) ends with `Can not find OpenCL library on this device` — the
 * fallback is statically-linked OpenCL too. There is no public API knob
 * to use the CPU sampler with a GPU engine. Using `Backend.CPU` for the
 * engine forces the CPU sampler and side-steps the issue entirely.
 *
 * Cost: on devices where GPU sampling DOES work (older Snapdragon with
 * full OpenCL drivers), we'd miss the GPU speedup. Acceptable, given
 * "always works on CPU" beats "fast on some devices, broken on Tensor".
 *
 * The runtime self-heal in [LiteRtLmSdkBridge.runCollect] still catches
 * the OpenCL error if it ever fires (e.g. someone explicitly forces a GPU
 * strategy) and blocklists the broken backend so subsequent calls try
 * the next candidate.
 */
object DefaultBackendStrategy : LiteRtLmBackendStrategy {
    override fun candidates(nativeLibDir: String): List<Pair<String, Backend>> {
        val list = mutableListOf<Pair<String, Backend>>()
        if (nativeLibDir.isNotBlank()) {
            list.add("NPU" to Backend.NPU(nativeLibDir))
        }
        // null = default thread count picked by the runtime.
        list.add("CPU" to Backend.CPU(null))
        list.add("GPU" to Backend.GPU())
        return list
    }
}

/**
 * `LiteRtLmNativeBridge` backed by `com.google.ai.edge.litertlm:litertlm-android`.
 *
 * Backend selection on `load()` is delegated to [LiteRtLmBackendStrategy].
 * The default ([DefaultBackendStrategy]) walks NPU → GPU → CPU, building a
 * fresh [Engine] per candidate until one initialises successfully. The chosen
 * backend is logged so the user-visible comparison screen can show why one
 * device is faster than another. Tests substitute their own strategy.
 *
 * No JSON-schema-constrained sampler exists in this SDK — [generateStructured]
 * appends the schema to the prompt as a hint and the parser in
 * `LiteRtLmModelProvider` does the defensive cleanup.
 */
class LiteRtLmSdkBridge(
    private val context: Context,
    private val backendStrategy: LiteRtLmBackendStrategy = DefaultBackendStrategy,
) : LiteRtLmNativeBridge {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var currentPath: String? = null
    private var currentBackend: String? = null

    /**
     * Backends that *initialised successfully* but then failed at runtime
     * during `generateContent` (e.g. Pixel 7a's Tensor G2 GPU loads fine but
     * the Top-K sampler tries to dlopen OpenCL and the Tensor stack has none,
     * so `runCollect` throws `Can not find OpenCL library on this device`).
     * Filtered out of subsequent loads in this process so the bridge doesn't
     * keep redoing the same dance every call. Cleared on app process death.
     */
    private val runtimeBlockedBackends = mutableSetOf<String>()

    private val classLoaderProbe: Boolean by lazy {
        try {
            Class.forName("com.google.ai.edge.litertlm.Engine")
            Log.i(TAG, "LiteRT-LM class loaded — runtime considered available")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "LiteRT-LM class not on classpath — runtime unavailable", t)
            false
        }
    }

    override fun isAvailable(): Boolean = classLoaderProbe

    override suspend fun load(absolutePath: String) {
        mutex.withLock {
            if (currentPath == absolutePath && engine != null) {
                Log.v(TAG, "load: already loaded $absolutePath, no-op")
                return
            }
            loadInternalLocked(absolutePath)
        }
    }

    /**
     * Same logic as [load] but assumes the caller already holds [mutex].
     * Exists so [runCollect] can reload the engine on the next backend after
     * an OpenCL-style runtime failure without dropping and re-acquiring the
     * mutex (which would let another caller race in mid-recovery).
     */
    private suspend fun loadInternalLocked(absolutePath: String) {
        withContext(Dispatchers.IO) {
            engine?.let {
                Log.i(TAG, "load: switching model — closing previous $currentPath")
                runCatching { it.close() }
            }
            engine = null
            currentPath = null
            currentBackend = null

            val cacheDir = File(context.cacheDir, "litertlm").also { it.mkdirs() }
            val nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty()
            val backendsToTry = backendStrategy.candidates(nativeLibDir)
                .filterNot { (label, _) -> label in runtimeBlockedBackends }

            if (backendsToTry.isEmpty()) {
                throw IllegalStateException(
                    "LiteRT-LM has no usable backends left for this session " +
                        "(all blocked by prior runtime failures: $runtimeBlockedBackends)",
                )
            }

            var lastError: Throwable? = null
            for ((label, backend) in backendsToTry) {
                val t0 = System.currentTimeMillis()
                Log.i(TAG, "load: trying backend=$label for $absolutePath")
                // visionBackend / audioBackend left null: every entry in
                // ModelCatalog is text-only. Setting them to `backend`
                // tells the engine to enable those modalities, and
                // initialize() then fails with `NOT_FOUND:
                // TF_LITE_VISION_ENCODER not found in the model.` for
                // text-only bundles (FunctionGemma 270M, Gemma 3 270M,
                // Qwen3 0.6B, etc.). When a true multi-modal Gemma 4 E2B
                // bundle is added later, switch this on per-entry.
                val candidate = Engine(
                    EngineConfig(
                        modelPath = absolutePath,
                        backend = backend,
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = null,
                        maxNumImages = null,
                        cacheDir = cacheDir.absolutePath,
                    ),
                )
                val initOk = runCatching { candidate.initialize() }
                if (initOk.isSuccess) {
                    engine = candidate
                    currentPath = absolutePath
                    currentBackend = label
                    Log.i(
                        TAG,
                        "load: ready on $label in ${System.currentTimeMillis() - t0}ms — $absolutePath",
                    )
                    return@withContext
                } else {
                    lastError = initOk.exceptionOrNull()
                    Log.w(
                        TAG,
                        "load: backend=$label failed (${lastError?.message}); trying next",
                    )
                    runCatching { candidate.close() }
                }
            }
            val tried = backendsToTry.joinToString(" → ") { it.first }
            throw IllegalStateException(
                "LiteRT-LM failed on every backend ($tried). Last error: ${lastError?.message}",
                lastError,
            )
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String =
        mutex.withLock { runCollect(prompt, maxTokens) }

    override suspend fun generateStructured(
        prompt: String,
        jsonSchema: String,
        maxTokens: Int,
    ): String = mutex.withLock {
        // No grammar-constrained sampler in this SDK — best the bridge can
        // do is push hard for the right shape. Show the provider's
        // example-output (passed in as `jsonSchema`) and explicitly forbid
        // commentary / markdown / code fences. The provider's parser falls
        // back to free-text extraction when this prompt nudge isn't enough.
        val combined = buildString {
            append(prompt)
            append("\n\nReturn ONLY a single JSON object on one line, matching this exact shape:\n")
            append(jsonSchema)
            append("\nDo not add prose, commentary, or code fences. Start your response with '{'.")
        }
        runCollect(combined, maxTokens)
    }

    private suspend fun runCollect(text: String, maxTokens: Int): String {
        return try {
            runCollectOnce(text, maxTokens)
        } catch (t: Throwable) {
            // Pixel 7a / Tensor G2: the GPU backend initialises fine but
            // generation throws `Can not find OpenCL library on this device`
            // because LiteRT-LM's Top-K sampler dlopens OpenCL even on the
            // WebGPU path. Blocklist that backend so the *next* call reloads
            // on the remaining strategy candidates (typically CPU).
            //
            // We deliberately do NOT reload + retry inline here. `Engine.close()`
            // doesn't release the GPU pipeline's native memory synchronously
            // — observed on Pixel 7a, the in-flight reload of the CPU engine
            // briefly held both pipelines in RAM and the process peaked at
            // ~5.96 GB, well past Pixel 7a's effective per-app budget. The
            // LMK reaped the app and the user saw an unexplained "LiteRT
            // crashed the app" with no FATAL exception in logcat. Bailing
            // out here keeps peak memory at 1× model and lets the very next
            // entry-point call (provider.generateXxx → bridge.load) start
            // from a clean slate with the blocklist already applied.
            val brokenBackend = currentBackend
            if (brokenBackend != null && isRecoverableRuntimeError(t)) {
                Log.w(
                    TAG,
                    "Recovering from $brokenBackend runtime failure (${t.message?.take(120)}) — " +
                        "blocklisting; next request will reload on remaining backends.",
                )
                runtimeBlockedBackends += brokenBackend
                runCatching { engine?.close() }
                engine = null
                currentPath = null
                currentBackend = null
            }
            throw t
        }
    }

    private suspend fun runCollectOnce(text: String, maxTokens: Int): String {
        val current = engine ?: error("LiteRT-LM: no model loaded")
        // maxNumTokens here is advisory — the SDK still respects the config-
        // level cap. We pass through whatever sampling the user requests.
        val session: Session = current.createSession(
            SessionConfig(SamplerConfig(40, 0.95, 0.7, 0)),
        )
        val t0 = System.currentTimeMillis()
        return try {
            withContext(Dispatchers.IO) {
                session.generateContent(listOf(InputData.Text(text)))
            }.also {
                Log.i(
                    TAG,
                    "runCollect: produced ${it.length} chars on $currentBackend in ${System.currentTimeMillis() - t0}ms",
                )
            }
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "runCollect: generation failed on $currentBackend after ${System.currentTimeMillis() - t0}ms",
                t,
            )
            throw t
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Recoverable = the engine loaded but a runtime feature it tried to use
     * isn't on this device. Right now the only known case is OpenCL missing
     * on Pixel Tensor; widen as we hit more.
     */
    private fun isRecoverableRuntimeError(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        return "opencl" in msg || "open cl" in msg
    }

    override suspend fun unload() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                engine?.let { runCatching { it.close() } }
            }
            engine = null
            currentPath = null
            currentBackend = null
        }
    }
}
