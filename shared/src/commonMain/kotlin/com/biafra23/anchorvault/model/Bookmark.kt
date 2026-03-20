package com.biafra23.anchorvault.model

import kotlinx.serialization.Serializable

/**
 * Core data model representing a saved URL bookmark with optional tags.
 */
@Serializable
data class Bookmark(
    val id: String,
    val url: String,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val isFavorite: Boolean = false
)
