# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# These rules will ensure that our generated serializers dont get obfuscated
-keep,includedescriptorclasses class com.hightouch.analytics.kotlin.**$$serializer { *; }
-keepclassmembers class com.hightouch.analytics.kotlin.** {
    *** Companion;
}
-keepclasseswithmembers class com.hightouch.analytics.kotlin.** {
    kotlinx.serialization.KSerializer serializer(...);
}