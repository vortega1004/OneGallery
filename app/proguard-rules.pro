# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# Coil 3
-dontwarn okio.**
-dontwarn okhttp3.**

# Keep domain models used via reflection-free Compose state
-keep class com.onegallery.app.domain.** { *; }

# NextLib (FFmpeg): its native code calls back into these classes by name
-keep class io.github.anilbeesetti.nextlib.** { *; }
