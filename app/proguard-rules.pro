# Keep the JNI entry points of the engine
-keep class dev.forgeai.LlamaEngine { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
