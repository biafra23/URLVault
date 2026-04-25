package com.biafra23.anchorvault

import platform.Foundation.NSBundle

actual object BuildConfig {
    actual val DEBUG: Boolean = NSBundle.mainBundle.pathForResource("Debug", "txt") != null
}
