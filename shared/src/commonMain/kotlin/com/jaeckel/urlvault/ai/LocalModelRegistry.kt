package com.jaeckel.urlvault.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the live set of installed/usable model providers. Both the Settings
 * UI and the comparison runner observe this flow so that downloads/deletions
 * propagate without restart.
 */
class LocalModelRegistry {

    private val _providers = MutableStateFlow<List<LocalModelProvider>>(emptyList())
    val providers: StateFlow<List<LocalModelProvider>> = _providers.asStateFlow()

    fun register(provider: LocalModelProvider) {
        _providers.update { current ->
            if (current.any { it.id == provider.id }) current
            else current + provider
        }
    }

    fun unregister(id: String) {
        _providers.update { current -> current.filterNot { it.id == id } }
    }

    fun providerById(id: String): LocalModelProvider? = _providers.value.firstOrNull { it.id == id }

    fun snapshot(): List<LocalModelProvider> = _providers.value
}
