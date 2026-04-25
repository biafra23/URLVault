package com.biafra23.anchorvault

actual object BuildConfig {
    actual val DEBUG: Boolean = System.getProperty("anchorvault.debug") == "true"
}
