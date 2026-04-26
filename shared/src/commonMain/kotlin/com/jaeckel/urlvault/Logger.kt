package com.jaeckel.urlvault

/**
 * Cross-platform logging interface.
 * Android implementation will use android.util.Log.
 * Other platforms (iOS, Desktop) will use println.
 */
expect object Logger {
    fun v(tag: String, message: String)
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
