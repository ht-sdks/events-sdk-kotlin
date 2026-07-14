# Releasing

The SDK is published through [**JitPack**](https://jitpack.io/#ht-sdks/events-sdk-kotlin/),
which builds each tagged commit of the public
[`ht-sdks/events-sdk-kotlin`](https://github.com/ht-sdks/events-sdk-kotlin) repository on
demand. There is no artifact-upload step — **tagging is the release.**

## How JitPack publishing works

When a consumer first requests a version, JitPack clones the matching git tag, runs the
build defined in [`jitpack.yml`](jitpack.yml) (`publishToMavenLocal` for each library
module), and serves the resulting artifacts under the group
`com.github.ht-sdks.events-sdk-kotlin`. Every module is published together at one
version — the git tag — so we version the whole library in lockstep.

## Cutting a release

1. **Bump the version.** These must all agree (CI enforces the first two via
   `./gradlew :core:verifyVersionSync`):
   - `VERSION_NAME` (and `VERSION_CODE`) in [`gradle.properties`](gradle.properties)
   - `LIBRARY_VERSION` in
     [`core/.../Constants.kt`](core/src/main/java/com/hightouch/analytics/kotlin/core/Constants.kt)

   `:android` and `:android-push` read their version from `VERSION_NAME` automatically —
   there is nothing extra to bump for them.

2. **Merge to `main`** and make sure the release commit is on the public `ht-sdks`
   remote. JitPack only sees the public repository.

3. **Tag and push** using the bare semver — no `v` prefix — matching the tag exactly to
   `VERSION_NAME`:

   ```bash
   git tag 0.0.1
   git push <public-remote> 0.0.1
   ```

4. **Verify the build** at <https://jitpack.io/#ht-sdks/events-sdk-kotlin/>: refresh until
   the new tag appears, click **Get it**, then open the build log. Confirm it succeeds and
   produces `core`, `android`, and `android-push`. The first request is what triggers the
   build, so doing this now means the first real consumer doesn't wait.

## Consuming a release

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

`android-push` depends on `android`, which depends on `core`, so most apps need only one
line — the transitive modules come along automatically.
