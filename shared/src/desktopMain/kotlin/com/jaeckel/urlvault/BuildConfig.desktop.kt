package com.jaeckel.urlvault

actual object BuildConfig {
    actual val DEBUG: Boolean = System.getProperty("urlvault.debug") == "true"
}
