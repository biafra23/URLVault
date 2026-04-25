package com.biafra23.urlvault

actual object BuildConfig {
    actual val DEBUG: Boolean = System.getProperty("urlvault.debug") == "true"
}
