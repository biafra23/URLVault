package com.jaeckel.urlvault

import android.util.Log

actual object Logger {
    actual fun v(tag: String, message: String) {
        Log.v(tag, message)
    }
    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
    }
    actual fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    actual fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
