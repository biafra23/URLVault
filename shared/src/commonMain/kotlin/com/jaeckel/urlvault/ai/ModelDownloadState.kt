package com.jaeckel.urlvault.ai

/**
 * Per-model download lifecycle. Held in a Map keyed by `ModelCatalogEntry.id`
 * inside the platform-specific `ModelDownloadManager`.
 */
sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data object Queued : ModelDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState() {
        val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }
    data object Verifying : ModelDownloadState()
    data class Ready(val absolutePath: String, val sizeBytes: Long) : ModelDownloadState()
    data class Failed(val reason: String) : ModelDownloadState()
}
