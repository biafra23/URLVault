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
            id = "llamacpp:gemma-4-e2b-it-q4_k_m",
            displayName = "Google Gemma 4 E2B IT (Q4_K_M, GGUF)",
            runtime = ModelRuntime.LLAMA_CPP,
            hfRepo = "bartowski/google_gemma-4-E2B-it-GGUF",
            hfFile = "google_gemma-4-E2B-it-Q4_K_M.gguf",
            approxBytes = 3463L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~3.5 GB download. Larger and slower than LFM2 but materially stronger on instruction following.",
        ),
        ModelCatalogEntry(
            id = "llamacpp:gemma-4-e2b-it-iq2_m",
            displayName = "Google Gemma 4 E2B IT (IQ2_M, GGUF)",
            runtime = ModelRuntime.LLAMA_CPP,
            hfRepo = "bartowski/google_gemma-4-E2B-it-GGUF",
            hfFile = "google_gemma-4-E2B-it-IQ2_M.gguf",
            approxBytes = 2499L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~2.5 GB. Smallest available Gemma 4 quant; noticeable quality drop vs. Q4_K_M but lower disk and RAM cost.",
        ),
        ModelCatalogEntry(
            id = "llamacpp:gemma-3-270m-it-q8_0",
            displayName = "Google Gemma 3 270M IT (Q8_0, GGUF)",
            runtime = ModelRuntime.LLAMA_CPP,
            hfRepo = "unsloth/gemma-3-270m-it-GGUF",
            hfFile = "gemma-3-270m-it-Q8_0.gguf",
            approxBytes = 278L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~278 MB. Tiny smoke-test model; useful for verifying the AI flow without " +
                "waiting through a 700+ MB download.",
        ),
        ModelCatalogEntry(
            id = "llamacpp:gemma-3-1b-it-q4_k_m",
            displayName = "Google Gemma 3 1B IT (Q4_K_M, GGUF)",
            runtime = ModelRuntime.LLAMA_CPP,
            hfRepo = "bartowski/google_gemma-3-1b-it-GGUF",
            hfFile = "google_gemma-3-1b-it-Q4_K_M.gguf",
            approxBytes = 806L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~800 MB. True 1B-param Gemma — same size class as LFM2 1.2B; one generation older than Gemma 4.",
        ),
        ModelCatalogEntry(
            id = "leap:lfm2-1.2b-extract",
            displayName = "Liquid AI LFM2 1.2B Extract (LEAP, JSON)",
            runtime = ModelRuntime.LEAP,
            hfRepo = "LiquidAI/LeapBundles",
            // Old-format bundle. Only loadable on leap-sdk <= 0.9.x —
            // 0.10.x dropped the loader for this filename pattern with
            // "No loader found for the options". We pin leap-sdk to 0.9.7
            // (see gradle/libs.versions.toml) specifically to keep this
            // bundle usable, since Liquid hasn't republished the Extract
            // fine-tune in the new '*_output_8da8w-seq_4096.bundle' format.
            hfFile = "lfm2-1.2B-extract-8da8w.bundle",
            approxBytes = 884L * 1024 * 1024,
            license = "LFM Open License",
            builtIn = true,
            notes = "Fine-tuned for structured JSON extraction. Runs through LeapSDK with " +
                "grammar-constrained JSON output, so tags / description / title are produced " +
                "as schema-validated JSON rather than free-form text.",
        ),
        ModelCatalogEntry(
            id = "litertlm:gemma-3-270m-it-q8",
            displayName = "Google Gemma 3 270M IT (LiteRT-LM, q8)",
            runtime = ModelRuntime.MEDIAPIPE,
            hfRepo = "litert-community/gemma-3-270m-it",
            hfFile = "gemma3-270m-it-q8.litertlm",
            approxBytes = 280L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~280 MB tiniest smoke-test. Gated on Hugging Face — needs " +
                "an HF token (Gemma terms acceptance). LiteRT-LM has no " +
                "grammar-constrained sampler, so JSON comes from prompt " +
                "instructions and a defensive parser, not a hard guarantee.",
        ),
        ModelCatalogEntry(
            id = "litertlm:functiongemma-270m-mobile-actions",
            displayName = "Google FunctionGemma 270M Mobile Actions (LiteRT-LM)",
            runtime = ModelRuntime.MEDIAPIPE,
            hfRepo = "litert-community/functiongemma-270m-ft-mobile-actions",
            hfFile = "mobile_actions_q8_ekv1024.litertlm",
            approxBytes = 280L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~280 MB. Fine-tuned for function-call (tool-use) JSON output, " +
                "the closest LiteRT-LM analogue to LFM2-Extract. Still no grammar " +
                "constraint — strict-JSON is best-effort via prompting. Gated; " +
                "needs an HF token.",
        ),
        ModelCatalogEntry(
            id = "litertlm:qwen3-0.6b",
            displayName = "Alibaba Qwen3 0.6B (LiteRT-LM)",
            runtime = ModelRuntime.MEDIAPIPE,
            hfRepo = "litert-community/Qwen3-0.6B",
            hfFile = "Qwen3-0.6B.litertlm",
            approxBytes = 586L * 1024 * 1024,
            license = "Apache 2.0",
            builtIn = true,
            notes = "~586 MB. Smallest open (ungated) LiteRT-LM model — no HF " +
                "login needed. Modern instruction-tuned chat; reasonable JSON " +
                "compliance via prompting alone.",
        ),
        ModelCatalogEntry(
            id = "litertlm:gemma-3-1b-it-int4",
            displayName = "Google Gemma 3 1B IT (LiteRT-LM, int4)",
            runtime = ModelRuntime.MEDIAPIPE,
            hfRepo = "litert-community/Gemma3-1B-IT",
            hfFile = "gemma3-1b-it-int4.litertlm",
            approxBytes = 620L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~620 MB. Same base model as the llama.cpp Gemma 3 1B entry, " +
                "via LiteRT-LM with NPU/GPU/CPU backend selection. Gated; needs " +
                "an HF token.",
        ),
        ModelCatalogEntry(
            id = "litertlm:gemma-4-e2b-it",
            displayName = "Google Gemma 4 E2B IT (LiteRT-LM, NPU)",
            runtime = ModelRuntime.MEDIAPIPE,
            hfRepo = "litert-community/gemma-4-E2B-it-litert-lm",
            hfFile = "gemma-4-E2B-it.litertlm",
            approxBytes = 2466L * 1024 * 1024,
            license = "Gemma Terms of Use",
            builtIn = true,
            notes = "~2.4 GB. Runs on NPU when the device exposes vendor libs " +
                "(Pixel Tensor / Qualcomm QNN), else GPU, else CPU — backend is " +
                "picked at load time. No grammar-constrained sampler, so JSON " +
                "comes from prompt instructions and a defensive parser.",
        ),
    )
}
