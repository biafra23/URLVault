package com.jaeckel.urlvault.model

import kotlinx.serialization.Serializable

/**
 * Represents a tag used for bookmark categorization and filtering.
 */
@Serializable
data class Tag(
    val name: String,
    val color: Long = 0xFF6200EE
)
