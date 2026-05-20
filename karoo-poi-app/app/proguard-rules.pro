# Karoo POI ProGuard rules

# Keep Room entities and DAOs (accessed via reflection)
-keep class com.karoopoi.data.** { *; }

# Keep Karoo extension models (serialized/deserialized)
-keep class com.karoopoi.extension.** { *; }
-keep class com.karoopoi.engine.** { *; }
-keep class com.karoopoi.geo.** { *; }

# Keep DataStore preferences (Proto/Serializable)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <init>(...); }

# Keep Kotlin coroutines (used heavily)
-keepnames class kotlinx.coroutines.** { *; }

# Don't warn about optional dependencies
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**