# Releasing

> **Status**: Publishing pipeline (JitPack vs Maven Central) is not yet decided. This document will be filled out once the publish target is chosen — see the rebrand plan at [`claude/plans/events-sdk-kotlin-rebrand.md`](claude/plans/events-sdk-kotlin-rebrand.md).

## Updating the version

Until publishing is wired up, version bumps still need to stay consistent across both files:

1. `VERSION_NAME` in [`gradle.properties`](gradle.properties)
2. `LIBRARY_VERSION` in [`core/src/main/java/com/hightouch/analytics/kotlin/core/Constants.kt`](core/src/main/java/com/hightouch/analytics/kotlin/core/Constants.kt)

These two must match. Phase 6 of the rebrand plan adds a CI check to enforce this.
