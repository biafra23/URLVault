# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Room entities and DAOs (Room's annotation processor handles most, but SQLCipher needs the entity classes)
-keep class com.biafra23.anchorvault.android.database.BookmarkEntity { *; }
-keep class com.biafra23.anchorvault.android.database.BookmarkDao { *; }
-keep class com.biafra23.anchorvault.android.database.AppDatabase { *; }

# Serializable model and sync classes (needed by kotlinx.serialization generated serializers)
-keepclassmembers class com.biafra23.anchorvault.model.Bookmark { *; }
-keepclassmembers class com.biafra23.anchorvault.sync.BitwardenCredentials { *; }

# SQLCipher
-keep class net.zetetic.** { *; }
-keep class net.zetetic.database.** { *; }

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

# Ktor — keep engine + SPI classes only (not entire package)
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.utils.io.** { *; }
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Koin — keep core reflection needs only
-keep class org.koin.core.** { *; }

# Google Tink (transitive dep from security-crypto) — annotation-only classes not bundled at runtime
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
