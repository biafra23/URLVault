package com.biafra23.urlvault

actual object Logger {
    actual fun v(tag: String, message: String) {
        println("[$tag] V: $message")
    }
    actual fun d(tag: String, message: String) {
        println("[$tag] D: $message")
    }
    actual fun i(tag: String, message: String) {
        println("[$tag] I: $message")
    }
    actual fun w(tag: String, message: String) {
        System.err.println("[$tag] W: $message")
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        System.err.println("[$tag] E: $message")
        throwable?.printStackTrace()
    }
}
