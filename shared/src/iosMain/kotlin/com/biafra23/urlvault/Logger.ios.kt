package com.biafra23.urlvault

import platform.Foundation.NSLog

actual object Logger {
    actual fun v(tag: String, message: String) {
        NSLog("[$tag] V: $message")
    }
    actual fun d(tag: String, message: String) {
        NSLog("[$tag] D: $message")
    }
    actual fun i(tag: String, message: String) {
        NSLog("[$tag] I: $message")
    }
    actual fun w(tag: String, message: String) {
        NSLog("[$tag] W: $message")
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("[$tag] E: $message - ${throwable.message}")
        } else {
            NSLog("[$tag] E: $message")
        }
    }
}
