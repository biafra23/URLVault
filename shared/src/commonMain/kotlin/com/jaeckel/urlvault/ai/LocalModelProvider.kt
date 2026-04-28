package com.jaeckel.urlvault.ai

import kotlinx.serialization.Serializable

/**
 * Pluggable on-device model. One instance = one runnable model variant
 * (e.g. a single Gemini Nano stage/preference combo, or one downloaded GGUF).
 */
interface LocalModelProvider {
    val id: String
    val displayName: String
    val runtime: ModelRuntime

    suspend fun isReady(): Boolean

    /**
     * Loads model weights into memory ahead of the first inference call so
     * comparison timings aren't skewed by cold-start cost. Idempotent — calling
     * twice is a no-op for the same path. Default is no-op for runtimes that
     * either lazy-load on first generate (and accept the bias) or are managed
     * by the platform (e.g. AICore).
     */
    suspend fun preload() {}

    suspend fun generateTags(url: String, title: String, content: String): Result<List<String>>
    suspend fun generateDescription(url: String, title: String): Result<String>
    suspend fun generateTitle(url: String): Result<String>
}

@Serializable
enum class ModelRuntime {
    ML_KIT,
    LLAMA_CPP,
    MEDIAPIPE,
    LEAP,
}
