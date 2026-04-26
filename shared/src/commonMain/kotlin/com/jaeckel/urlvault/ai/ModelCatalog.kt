package com.jaeckel.urlvault.ai

import kotlinx.serialization.Serializable

/**
 * One downloadable model entry. Built-in entries seed the catalog; users may
 * add custom HuggingFace repos at runtime.
 */
@Serializable
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val runtime: ModelRuntime,
    val hfRepo: String,
    val hfFile: String,
    val approxBytes: Long,
    val license: String,
    val builtIn: Boolean,
    val notes: String = "",
) {
    /** Direct download URL on huggingface.co for this single file. */
    fun downloadUrl(): String = "https://huggingface.co/$hfRepo/resolve/main/$hfFile"

    /** Local filename to use when persisting the download. */
    fun localFileName(): String = "${id}__${hfFile.substringAfterLast('/')}"
}

object ModelCatalog {
    /**
     * Seed list. LFM2 is active this session (llama.cpp); Gemma 3 is shown
     * as a placeholder until the MediaPipe session lands.
     */
    val builtIn: List<ModelCatalogEntry> = listOf(
        ModelCatalogEntry(
            id = "llamacpp:lfm2-1.2b-q4_k_m",
            displayName = "Liquid AI LFM2 1.2B (Q4_K_M, GGUF)",
            runtime = ModelRuntime.LLAMA_CPP,
            hfRepo = "LiquidAI/LFM2-1.2B-GGUF",
            hfFile = "LFM2-1.2B-Q4_K_M.gguf",
            approxBytes = 800L * 1024 * 1024,
            license = "LFM Open License",
            builtIn = true,
        ),
        ModelCatalogEntry(
            id = "mediapipe:gemma-3-1b-it",
            displayName = "Google Gemma 3 1B IT (Coming soon)",
            runtime = ModelRuntime.MEDIAPIPE,
            hfRepo = "google/gemma-3-1b-it",
            hfFile = "gemma-3-1b-it.task",
            approxBytes = 700L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "MediaPipe runtime not yet wired up — enable in the next session.",
        ),
    )
}
