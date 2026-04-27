package com.jaeckel.urlvault.ai

/**
 * Stable provider identifiers shared across the registry, the active-id
 * preference set, and the Settings UI. The router and the Settings UI both
 * need to recognize the AICore adapter without depending on Android-only code.
 */
object AiProviderIds {
    /** Matches `AICoreServiceAdapter.id`. */
    const val AICORE = "mlkit:gemini-nano-active"
}
