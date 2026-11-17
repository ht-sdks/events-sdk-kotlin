# Releasing

The SDK is published to **Maven Central** under the group `com.hightouch.analytics.kotlin`
by the [tagged-release](.github/workflows/tagged-release.yml) workflow. Pushing a version
tag to the public [`ht-sdks/events-sdk-kotlin`](https://github.com/ht-sdks/events-sdk-kotlin)
repository is the release.

[JitPack](https://jitpack.io/#ht-sdks/events-sdk-kotlin/) also keeps building tags on demand
(via [`jitpack.yml`](jitpack.yml)) for consumers still on the old
`com.github.ht-sdks.events-sdk-kotlin` coordinates. There is no separate JitPack step.

Every module (`core`, `android`, `android-push`) is published together at one version, so
the whole library is versioned in lockstep.

## How Maven Central publishing works

The library modules apply the
[vanniktech `gradle-maven-publish-plugin`](https://vanniktech.github.io/gradle-maven-publish-plugin/central/),
the same setup as [`events-sdk-android`](https://github.com/ht-sdks/events-sdk-android).
The plugin builds the POM from the `POM_*` properties in [`gradle.properties`](gradle.properties)
(plus each module's `POM_NAME` / `POM_ARTIFACT_ID`), attaches the sources and javadoc jars,
signs everything with GPG, and uploads to the
[Sonatype Central Portal](https://central.sonatype.com/publishing), which releases it
automatically once validation passes.

The workflow needs these repository secrets:

| Secret | What it is |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | Central Portal user token with publish rights for `com.hightouch.analytics.kotlin` |
| `GPG_SIGNING_KEY` / `GPG_SIGNING_KEY_PASSWORD` | ASCII-armored private key used to sign artifacts, and its passphrase |

Signing is only enabled in that workflow, so `publishToMavenLocal` and JitPack work without
a key.

> **Maven Central releases are permanent.** A published version can never be changed or
> deleted. If something is wrong, ship a new version.

## Cutting a release

1. **Bump the version.** These must all agree (CI enforces the first two via
   `./gradlew :core:verifyVersionSync`):
   - `VERSION_NAME` (and `VERSION_CODE`) in [`gradle.properties`](gradle.properties)
   - `LIBRARY_VERSION` in
     [`core/.../Constants.kt`](core/src/main/java/com/hightouch/analytics/kotlin/core/Constants.kt)
   - The install snippet in [`README.md`](README.md)

   `:android` and `:android-push` read their version from `VERSION_NAME` automatically.
   There is nothing extra to bump for them.

2. **Merge to `main`.**

3. **Tag and push** using the bare semver (no `v` prefix), matching `VERSION_NAME` exactly.
   The workflow fails fast if they differ.

   ```bash
   git tag 0.1.0
   git push origin 0.1.0
   ```

4. **Watch the [tagged-release](https://github.com/ht-sdks/events-sdk-kotlin/actions/workflows/tagged-release.yml)
   run**, then confirm the deployment on the
   [Central Portal Deployments](https://central.sonatype.com/publishing) page. **A green
   CI run is not enough:** CI exits right after uploading and doesn't wait for the Portal's
   validation, so a deployment that fails validation only shows up there. The artifacts
   show up on the public
   [artifact page](https://central.sonatype.com/artifact/com.hightouch.analytics.kotlin/android)
   and become resolvable from `mavenCentral()` within roughly 15–30 minutes.

## Testing a release locally

```bash
./gradlew :core:publishToMavenLocal :android:publishToMavenLocal :android-push:publishToMavenLocal
```

This writes the artifacts to `~/.m2/repository/com/hightouch/analytics/kotlin/` at
`VERSION_NAME`. Inspect the generated `.pom` files there, or point a test app at them by
adding `mavenLocal()` ahead of `mavenCentral()` in its repositories.

## Consuming a release

```gradle
repositories { mavenCentral() }

dependencies {
    // Android + push notifications:
    implementation 'com.hightouch.analytics.kotlin:android-push:<version>'
    // …or Android without FCM push:
    implementation 'com.hightouch.analytics.kotlin:android:<version>'
    // …or pure JVM:
    implementation 'com.hightouch.analytics.kotlin:core:<version>'
}
```

`android-push` depends on `android`, which depends on `core`, so most apps need only one
line. The transitive modules come along automatically.
