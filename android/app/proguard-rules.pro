# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.kairo.core.model.** { *** Companion; }
-keepclasseswithmembers class app.kairo.core.model.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.kairo.**$$serializer { *; }

# Vosk / JNA
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.**
