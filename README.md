# Events SDK Kotlin

The Hightouch Events SDK for Kotlin (Android / JVM).

Forked from [`segmentio/analytics-kotlin`](https://github.com/segmentio/analytics-kotlin) — same Timeline + Plugin architecture, rebranded and rewired to send events to Hightouch.

## Status

Pre-release. Publishing is not yet wired up — see [`claude/plans/events-sdk-kotlin-rebrand.md`](claude/plans/events-sdk-kotlin-rebrand.md) for the rebrand plan.

## Installation

> **TODO**: Publish target (JitPack / Maven Central) to be decided. Build from source for now.

```bash
git clone git@github.com:hightouchio/events-sdk-kotlin.git
cd events-sdk-kotlin
./gradlew :core:build :android:build
```

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

Requires **JDK 11** (transitive MockK pin is incompatible with JDK 17+).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
./gradlew check build assembleAndroidTest
```

## License

MIT — see [`LICENSE`](LICENSE).
