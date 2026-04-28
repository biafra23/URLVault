package com.jaeckel.urlvault.android.ai

import android.util.Log
import ai.liquid.leap.GenerationOptions
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.message.MessageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LeapSdkNativeBridge"

/**
 * `LeapNativeBridge` backed by Liquid AI's LeapSDK. LeapSDK ships native
 * libraries in its AAR; we never write JNI in this repo.
 *
 * Each call creates a fresh `Conversation` from the loaded `ModelRunner` —
 * the three bookmark AI calls (tags / description / title) are logically
 * independent extractions, not a chat dialogue, so we explicitly avoid
 * carrying history across them.
 *
 * Structured output goes through `GenerationOptions.jsonSchemaConstraint`,
 * which Leap turns into a grammar-constrained sampler at the token level
 * (and additionally injects the schema into the system prompt for semantic
 * guidance — see `injectSchemaIntoPrompt`). The returned text is a JSON
 * value of the requested shape; downstream parsing in `LeapModelProvider`
 * just needs to handle whitespace.
 */
class LeapSdkNativeBridge : LeapNativeBridge {

    private val mutex = Mutex()
    private var runner: ModelRunner? = null
    private var currentPath: String? = null

    private val classLoaderProbe: Boolean by lazy {
        try {
            Class.forName("ai.liquid.leap.LeapClient")
            Log.i(TAG, "LeapSDK class loaded — runtime considered available")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "LeapSDK class not on classpath — runtime unavailable", t)
            false
        }
    }

    override fun isAvailable(): Boolean = classLoaderProbe

    override suspend fun load(absolutePath: String) {
        mutex.withLock {
            if (currentPath == absolutePath && runner != null) {
                Log.v(TAG, "load: already loaded $absolutePath, no-op")
                return
            }
            withContext(Dispatchers.IO) {
                runner?.let {
                    Log.i(TAG, "load: switching model — unloading previous $currentPath")
                    runCatching { it.unload() }
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

    // 0.9.7's GenerationOptions exposes temperature / topP / minP /
    // repetitionPenalty / jsonSchemaConstraint / functionCallParser /
    // inlineThinkingTags / extras — but no maxTokens or
    // injectSchemaIntoPrompt. The maxTokens parameter on these methods is
    // therefore advisory; we still honour it via runCollect's bookkeeping
    // for logging, but the SDK doesn't get a hard cap. Schema injection
    // into the prompt is handled by 0.9.7 implicitly when
    // jsonSchemaConstraint is non-null.
    override suspend fun generate(prompt: String, maxTokens: Int): String =
        mutex.withLock {
            runCollect(prompt, GenerationOptions())
        }

    override suspend fun generateStructured(
        prompt: String,
        jsonSchema: String,
        maxTokens: Int,
    ): String = mutex.withLock {
        val options = GenerationOptions().apply {
            jsonSchemaConstraint = jsonSchema
        }
        runCollect(prompt, options)
    }

    private suspend fun runCollect(userText: String, options: GenerationOptions): String {
        val current = runner ?: error("LeapSDK: no model loaded")
        // Fresh conversation per call — these are three independent extractions,
        // not a chat dialogue. Carrying history would let one call's prompt
        // bleed into the next.
        val conversation = current.createConversation()
        val builder = StringBuilder()
        val t0 = System.currentTimeMillis()
        try {
            withContext(Dispatchers.IO) {
                conversation.generateResponse(userText, options).collect { response ->
                    when (response) {
                        is MessageResponse.Chunk -> builder.append(response.text)
                        is MessageResponse.Complete,
                        is MessageResponse.ReasoningChunk,
                        is MessageResponse.FunctionCalls,
                        is MessageResponse.AudioSample -> {
                            // Not relevant for our text-only use case.
                        }
                    }
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

    override suspend fun unload() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runner?.let { runCatching { it.unload() } }
            }
            runner = null
            currentPath = null
        }
    }
}
