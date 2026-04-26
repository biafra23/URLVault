package com.jaeckel.urlvault.android.ai

/**
 * Thin abstraction over a native llama.cpp wrapper. Kept as an interface so the
 * repository builds whether or not a JNI-backed wrapper is on the classpath.
 *
 * To enable real LFM2 / GGUF inference, add a llama.cpp Android Gradle dep
 * (one that ships prebuilt arm64-v8a / x86_64 .so files) and provide an
 * implementation that delegates to it. Candidates:
 *   - io.github.shubham0204:smollm-android
 *   - the LLamaAndroid example from the official llama.cpp repo, vendored
 *     as a separate module
 *
 * Then register the implementation in `AppModule.kt` in place of
 * `NoOpLlamaCppNativeBridge`.
 *
 * No JNI is written in this repository — JNI lives entirely inside the chosen
 * AAR.
 */
interface LlamaCppNativeBridge {
    /** Whether a real native runtime is available on this device. */
    fun isAvailable(): Boolean

    /** Loads the GGUF at [absolutePath] into memory. Idempotent per path. */
    suspend fun load(absolutePath: String)

    /** Generates [maxTokens] tokens of completion for [prompt]. */
    suspend fun generate(prompt: String, maxTokens: Int = 256): String

    /** Frees native resources for the currently loaded model. */
    suspend fun unload()
}

/**
 * Default implementation used when no llama.cpp wrapper is on the classpath.
 * Surfaces a clear message in `ModelDownloadState.Failed` / comparison error
 * cells so the user knows the architecture is wired up but the runtime is not.
 */
object NoOpLlamaCppNativeBridge : LlamaCppNativeBridge {
    override fun isAvailable(): Boolean = false

    override suspend fun load(absolutePath: String) {
        error(
            "No llama.cpp wrapper is linked. Add a Gradle dep that ships prebuilt " +
                ".so files and register a real LlamaCppNativeBridge in AppModule.kt."
        )
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        error("No llama.cpp wrapper is linked.")
    }

    override suspend fun unload() {}
}
