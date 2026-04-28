package com.jaeckel.urlvault.android.ai

import android.util.Log
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.MessageResponse
import ai.liquid.leap.message.UserChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LeapSdkNativeBridge"

/**
 * `LeapNativeBridge` backed by Liquid AI's LeapSDK
 * (`ai.liquid.leap:leap-sdk`). LeapSDK ships native libraries in its AAR;
 * we never write JNI in this repo.
 *
 * Like the Llamatik bridge, this owns a single process-wide `ModelRunner`
 * and serializes load/generate/unload behind a [Mutex]. Per-provider load
 * tracking lives elsewhere — the bridge is the source of truth.
 *
 * SDK note: the snapshot 0.10.0-5 publishes
 *   - `LeapClient.loadModel(filePath: String): ModelRunner`
 *   - `ModelRunner.generateResponse(message: ChatMessage): Flow<MessageResponse>`
 *   - `ModelRunner.unload()` (closes native handles)
 * If the user upgrades and method/class names shift, only this file needs
 * to follow upstream — the bridge interface stays stable.
 *
 * Structured output: rather than depending on a particular Constraints DSL
 * (which has shifted across snapshots), [generateStructured] inlines the
 * JSON schema into the prompt and asks the model to obey it. LFM2 1.2B
 * Extract is fine-tuned for this and reliably emits clean JSON; downstream
 * parsing in `LeapModelProvider` handles the leading/trailing whitespace
 * and any stray prose.
 */
class LeapSdkNativeBridge : LeapNativeBridge {

    private val mutex = Mutex()
    private var runner: ModelRunner? = null
    private var currentPath: String? = null

    private val available: Boolean by lazy {
        // We can't probe the SDK without a model file, so this flag really
        // means "the LeapClient class loaded without a NoClassDefFoundError".
        // load() itself surfaces any per-device runtime issues.
        try {
            Class.forName("ai.liquid.leap.LeapClient")
            Log.i(TAG, "LeapSDK class loaded — runtime considered available")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "LeapSDK class not on classpath — runtime unavailable", t)
            false
        }
    }

    override fun isAvailable(): Boolean = available

    override suspend fun load(absolutePath: String) {
        mutex.withLock {
            if (currentPath == absolutePath && runner != null) {
                Log.v(TAG, "load: already loaded $absolutePath, no-op")
                return
            }
            withContext(Dispatchers.IO) {
                runner?.let {
                    Log.i(TAG, "load: switching model — closing previous $currentPath")
                    runCatching { closeRunner(it) }
                }
                runner = null
                currentPath = null
                val t0 = System.currentTimeMillis()
                Log.i(TAG, "load: LeapClient.loadModel($absolutePath)")
                runner = try {
                    LeapClient.loadModel(absolutePath)
                } catch (t: Throwable) {
                    Log.e(TAG, "load: LeapClient.loadModel threw", t)
                    throw t
                }
                currentPath = absolutePath
                Log.i(TAG, "load: ready in ${System.currentTimeMillis() - t0}ms — $absolutePath")
            }
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String =
        mutex.withLock { runCollect(UserChatMessage(prompt)) }

    override suspend fun generateStructured(
        prompt: String,
        jsonSchema: String,
        maxTokens: Int,
    ): String = mutex.withLock {
        // Schema is inlined into the prompt rather than passed via a SDK
        // Constraints DSL, see class comment above. The Extract model is
        // fine-tuned to honor this layout.
        val structuredPrompt = buildString {
            appendLine("You are a strict JSON extractor.")
            appendLine("Reply with ONLY a JSON value that conforms to the following JSON schema.")
            appendLine("Do not include markdown fences, prose, or any text outside the JSON.")
            appendLine()
            appendLine("Schema:")
            appendLine(jsonSchema)
            appendLine()
            appendLine("Task:")
            append(prompt)
        }
        runCollect(UserChatMessage(structuredPrompt))
    }

    private suspend fun runCollect(message: ChatMessage): String {
        val current = runner ?: error("LeapSDK: no model loaded")
        val builder = StringBuilder()
        val t0 = System.currentTimeMillis()
        try {
            withContext(Dispatchers.IO) {
                current.generateResponse(message).collect { response ->
                    // The sealed hierarchy varies across snapshots; we read
                    // text via reflection on the response so a missing
                    // ReasoningChunk / Complete subtype doesn't break us.
                    extractTextChunk(response)?.let { builder.append(it) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "runCollect: generation failed after ${System.currentTimeMillis() - t0}ms", t)
            throw t
        }
        val out = builder.toString()
        Log.i(
            TAG,
            "runCollect: produced ${out.length} chars in ${System.currentTimeMillis() - t0}ms",
        )
        return out
    }

    /**
     * Read the streamed text chunk out of a [MessageResponse] without
     * pattern-matching the sealed subtypes. The set of subtypes (Chunk,
     * Complete, ReasoningChunk, FunctionCalls, ...) has shifted across
     * snapshots; reflection on a public `text` field/property is the
     * lowest-friction way to remain compatible.
     */
    private fun extractTextChunk(response: MessageResponse): String? {
        // Most snapshots expose `text: String` on the streamed-content type
        // and either no `text` (or empty) on the terminal Complete type.
        return runCatching {
            val cls = response::class.java
            val getter = cls.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                ?: cls.methods.firstOrNull { it.name == "text" && it.parameterCount == 0 }
            (getter?.invoke(response) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    override suspend fun unload() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runner?.let { runCatching { closeRunner(it) } }
            }
            runner = null
            currentPath = null
        }
    }

    /**
     * Snapshot LeapSDK versions vary between `unload()`, `close()`,
     * `release()`, and `shutdown()` for runner cleanup. Try each in order
     * via reflection so we never compile against a name that disappears.
     */
    private fun closeRunner(r: ModelRunner) {
        val candidates = listOf("unload", "close", "release", "shutdown")
        for (name in candidates) {
            val m = r::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (m != null) {
                m.invoke(r)
                return
            }
        }
        Log.w(TAG, "closeRunner: no known cleanup method on ${r::class.java.name}")
    }
}
