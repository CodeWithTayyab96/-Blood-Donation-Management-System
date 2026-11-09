# Keep model/data classes used with org.json serialisation (SupabaseClient).
-keep class com.tayyab.blooddonation.model.** { *; }

# Keep org.json usage (Android platform classes, but R8 can optimise call sites).
-keep class org.json.** { *; }

# Firebase Messaging (reflection-heavy).
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# AndroidX + Material (safe defaults).
-dontwarn androidx.**
-keep class androidx.core.app.NotificationCompat** { *; }

# Keep BuildConfig (read at runtime by AppUpdater / Push).
-keep class com.tayyab.blooddonation.BuildConfig { *; }

# Kotlin metadata (needed for reflection if any).
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
