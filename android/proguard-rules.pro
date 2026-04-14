# Gemma Shelf Scanner Plugin — ProGuard Rules

# Keep Capacitor plugin class and annotated methods
-keep class com.gamma4.shelfscanner.ShelfScannerPlugin {
    @com.getcapacitor.PluginMethod *;
}

# Keep data classes (used by Gson serialization)
-keep class com.gamma4.shelfscanner.model.** { *; }

# Keep LiteRT-LM classes
-keep class com.google.ai.edge.litertlm.** { *; }

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
