# Events SDK Kotlin

The Hightouch Events SDK for Kotlin (Android / JVM).

Forked from [`segmentio/analytics-kotlin`](https://github.com/segmentio/analytics-kotlin) — same Timeline + Plugin architecture, rebranded and rewired to send events to Hightouch.

## Installation

Published via [JitPack](https://jitpack.io/#ht-sdks/events-sdk-kotlin/). Add the JitPack
repository and pick the module you need:

```gradle
repositories { maven { url 'https://jitpack.io' } }

dependencies {
    // Android + push notifications:
    implementation 'com.github.ht-sdks.events-sdk-kotlin:android-push:0.0.1'
    // …or Android without FCM push:
    implementation 'com.github.ht-sdks.events-sdk-kotlin:android:0.0.1'
    // …or pure JVM:
    implementation 'com.github.ht-sdks.events-sdk-kotlin:core:0.0.1'
}
```

See [`RELEASING.md`](RELEASING.md) for how releases are cut.

## Initialization

Kotlin (Android):

```kotlin
import com.hightouch.analytics.kotlin.android.Analytics
import com.hightouch.analytics.kotlin.core.Configuration

val analytics = Analytics("<WRITE_KEY>", applicationContext) {
    apiHost = "<API_HOST>/v1"
    trackApplicationLifecycleEvents = true
    flushInterval = 10
}

analytics.track("Application Started")
analytics.identify("user-123")
analytics.screen("Home")
```

Java (Android):

```java
import com.hightouch.analytics.kotlin.android.Analytics;
import com.hightouch.analytics.kotlin.core.Configuration;

Analytics analytics = AnalyticsKt.Analytics("<WRITE_KEY>", getApplicationContext(), config -> {
    config.setApiHost("<API_HOST>/v1");
    config.setTrackApplicationLifecycleEvents(true);
    return null;
});

analytics.track("Application Started");
```

See [`JAVA_COMPAT.md`](JAVA_COMPAT.md) for the full Java interop surface.

## Building

Requires **JDK 17** (Android Gradle Plugin 8.5+).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
./gradlew check build assembleAndroidTest
```

## License

MIT — see [`LICENSE`](LICENSE).
