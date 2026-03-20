# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Room database classes
-keep class com.biafra23.anchorvault.android.database.** { *; }
-keep class com.biafra23.anchorvault.model.** { *; }
-keep class com.biafra23.anchorvault.sync.** { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.biafra23.anchorvault.**$$serializer { *; }
-keepclassmembers class com.biafra23.anchorvault.** {
    *** Companion;
}
-keepclasseswithmembers class com.biafra23.anchorvault.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Koin
-keep class org.koin.** { *; }
