# Events SDK Kotlin

The Hightouch Events SDK for Kotlin (Android / JVM).

Forked from [`segmentio/analytics-kotlin`](https://github.com/segmentio/analytics-kotlin) — same Timeline + Plugin architecture, rebranded and rewired to send events to Hightouch.

## Installation

Published to [Maven Central](https://central.sonatype.com/artifact/com.hightouch.analytics.kotlin/android).
Pick the module you need:

```gradle
repositories { mavenCentral() }

dependencies {
    // Android + push notifications:
    implementation 'com.hightouch.analytics.kotlin:android-push:0.1.0'
    // …or Android without FCM push:
    implementation 'com.hightouch.analytics.kotlin:android:0.1.0'
    // …or pure JVM:
    implementation 'com.hightouch.analytics.kotlin:core:0.1.0'
}
```

`android-push` depends on `android`, which depends on `core`, so you only need one line.

### JitPack

Every release is also available from [JitPack](https://jitpack.io/#ht-sdks/events-sdk-kotlin/)
under the `com.github.ht-sdks.events-sdk-kotlin` group:

```gradle
repositories { maven { url 'https://jitpack.io' } }

dependencies {
    implementation 'com.github.ht-sdks.events-sdk-kotlin:android-push:0.1.0'
}
```

Use **one** source, not both. Gradle treats the two groups as different libraries, so mixing
them puts duplicate classes on the classpath. If you're moving from JitPack to Maven Central,
replace every `com.github.ht-sdks.events-sdk-kotlin` dependency with the matching
`com.hightouch.analytics.kotlin` one.

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
