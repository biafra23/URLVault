package com.jaeckel.urlvault.android.ai

/**
 * Thin abstraction over Liquid AI's LeapSDK runtime.
 *
 * Mirrors `LlamaCppNativeBridge` in spirit but exposes a structured-output
 * entry point: [generateStructured] takes a JSON schema string and returns a
 * model response that conforms to it. LeapSDK enforces this via grammar-
 * constrained decoding, so the caller can rely on parsing the result.
 *
 * The interface keeps the build green even if the SDK class names shift
 * across snapshot versions — only the concrete implementation needs to track
 * upstream API changes.
 */
interface LeapNativeBridge {
    /** Whether LeapSDK loaded successfully and the device can run inference. */
    fun isAvailable(): Boolean

    /**
     * Loads the `.bundle` at [absolutePath] into memory. Idempotent per path:
     * a repeated call with the same path is a no-op; a different path causes
     * the previously-loaded model to be unloaded first.
     */
    suspend fun load(absolutePath: String)

    /**
     * Runs free-text generation. Used for the title fallback path where the
     * page itself doesn't expose a usable `<title>` and the model is asked
     * for a short phrase rather than a JSON object.
     */
    suspend fun generate(prompt: String, maxTokens: Int = 128): String

    /**
     * Runs schema-constrained generation. [jsonSchema] is a JSON Schema
     * (Draft-07 or compatible) string; the returned text is guaranteed by
     * LeapSDK's grammar-constrained sampler to be a valid JSON value of the
     * requested shape (assuming the SDK build supports JSON-schema decoding;
     * if it falls back to free-form text on this device, the response may
     * still be plausible JSON but isn't grammar-enforced).
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
 * Default fallback implementation used when LeapSDK isn't on the classpath
 * or fails to initialize. Surfaces a clear error in comparison-screen cells
 * so the architecture is visibly wired up while the runtime isn't loaded.
 */
object NoOpLeapNativeBridge : LeapNativeBridge {
    override fun isAvailable(): Boolean = false

    override suspend fun load(absolutePath: String) {
        error(
            "LeapSDK runtime not initialized. Check `ai.liquid.leap:leap-sdk` is on " +
                "the classpath and that LeapSdkNativeBridge is registered in AppModule.kt.",
        )
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        error("LeapSDK runtime not initialized.")
    }

    override suspend fun generateStructured(
        prompt: String,
        jsonSchema: String,
        maxTokens: Int,
    ): String {
        error("LeapSDK runtime not initialized.")
    }

    override suspend fun unload() {}
}
