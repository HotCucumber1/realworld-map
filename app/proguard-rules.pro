# kotlinx.serialization: keep generated serializers of @Serializable classes (backup, sync DTOs, routes).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }

# MapLibre uses JNI and reflection from native code.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# OkHttp optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
