# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep generated serializers for Hightouch push types
-keep,includedescriptorclasses class com.hightouch.analytics.kotlin.push.**$$serializer { *; }
-keepclassmembers class com.hightouch.analytics.kotlin.push.** {
    *** Companion;
}
-keepclasseswithmembers class com.hightouch.analytics.kotlin.push.** {
    kotlinx.serialization.KSerializer serializer(...);
}
