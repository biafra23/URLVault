package com.jaeckel.urlvault.android.ai

/**
 * Thin abstraction over Google's LiteRT-LM runtime
 * (`com.google.ai.edge.litertlm:litertlm-android`).
 *
 * Mirrors `LeapNativeBridge` and `LlamaCppNativeBridge` so the Compose UI and
 * `LocalModelRegistry` can treat all on-device runtimes uniformly. LiteRT-LM
 * does not natively expose a JSON-schema-constrained sampler the way LeapSDK
 * does, so [generateStructured] here returns *plausibly-shaped* JSON via
 * prompt engineering — callers must still parse defensively.
 *
 * Backend selection (NPU / GPU / CPU) is the implementation's responsibility:
 * the bridge tries NPU first when the device exposes vendor libs, falls back
 * to GPU, and finally to CPU.
 */
interface LiteRtLmNativeBridge {
    /** Whether LiteRT-LM loaded successfully and the device can run inference. */
    fun isAvailable(): Boolean

    /**
     * Loads the `.litertlm` bundle at [absolutePath] into memory. Idempotent
     * per path: a repeated call with the same path is a no-op; a different
     * path causes the previously-loaded model to be unloaded first.
     */
    suspend fun load(absolutePath: String)

    /** Free-text generation. Used for the title fallback path. */
    suspend fun generate(prompt: String, maxTokens: Int = 256): String

    /**
     * Generation with a JSON-schema *hint*. LiteRT-LM doesn't grammar-constrain
     * the sampler, so this is implemented as prompt engineering — the schema
     * is appended to the prompt and the model is asked to respond in JSON.
     * Callers must defend against malformed output (extract the largest
     * plausible JSON object, then parse).
     */
    suspend fun generateStructured(
        prompt: String,
        jsonSchema: String,
        maxTokens: Int = 192,
    ): String

    /** Frees native resources for the currently loaded model. */
    suspend fun unload()
}

/**
 * Default fallback used when the LiteRT-LM SDK isn't on the classpath or
 * fails to initialise — surfaces a clear error in the comparison UI rather
 * than silently disappearing.
 */
object NoOpLiteRtLmNativeBridge : LiteRtLmNativeBridge {
    override fun isAvailable(): Boolean = false

    override suspend fun load(absolutePath: String) {
        error(
            "LiteRT-LM runtime not initialised. Check " +
                "`com.google.ai.edge.litertlm:litertlm-android` is on the classpath " +
                "and that LiteRtLmSdkBridge is registered in AppModule.kt.",
        )
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        error("LiteRT-LM runtime not initialised.")
    }

    override suspend fun generateStructured(
        prompt: String,
        jsonSchema: String,
        maxTokens: Int,
    ): String {
        error("LiteRT-LM runtime not initialised.")
    }

    override suspend fun unload() {}
}
