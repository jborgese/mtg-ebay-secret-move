# Default ProGuard rules pulled in by getDefaultProguardFile("proguard-android-optimize.txt").
# Additional rules below are project-specific. R8 will treat them as keep rules.

# Kotlinx Serialization — required so generated serializers aren't stripped.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mtgebay.app.**$$serializer { *; }
-keepclassmembers class com.mtgebay.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.mtgebay.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit (will become relevant in Phase 3)
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# OkHttp (Phase 3)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Compose tooling preview — keep classes annotated for tooling so previews don't break debug builds.
-keep class androidx.compose.ui.tooling.preview.** { *; }
