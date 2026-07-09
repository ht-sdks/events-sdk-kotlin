# Silent push (background data)

A silent push displays **no notification**. It wakes your app in the background and delivers
marketer-defined `customData` (string key/value pairs) to a listener you register on the SDK
config. Use it to prefetch content, update local state, or trigger your own background work in
response to a campaign.

## Receiving silent pushes

Register a `HightouchSilentPushListener` on the config, and initialize the SDK in
`Application.onCreate`:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HightouchPush.initialize(
            context = this,
            writeKey = "YOUR_WRITE_KEY",
            config = HightouchPushConfig.Builder("YOUR_APP_ID")
                .setSilentPushListener { customData ->
                    // e.g. persist, prefetch, or schedule work based on customData
                }
                .build(),
        )
    }
}
```

**Initialization placement matters.** FCM can cold-start your app process in the background to
deliver a silent push. Only code in `Application.onCreate` runs before the message is handled, so
a listener registered later (for example from an Activity) would miss pushes that arrive while
the app isn't running.

## Semantics

- The listener fires **only for silent pushes with non-empty `customData`** — it's a pure
  data-consumption channel. A silent push without `customData` is consumed with no callback.
- Visible pushes never fire this listener. Their `customData` still reaches your app on tap —
  see [Handlers & deep links](handlers-and-deep-links.md#action-context--customdata).
- No notification is posted and no engagement events are emitted for silent pushes (there is
  nothing to open).
- If a silent push arrives and no listener is registered, the SDK logs a warning and drops the
  data.

## Threading

The listener is invoked **on FCM's background message-handling thread**, not the main thread.
Keep it short — the OS grants a limited execution window for background message handling
(typically around 10 seconds). For anything long-running, persist what you need or hand off to
`WorkManager` and return.

If the listener throws, the SDK catches and logs the exception so a host-app bug can't crash
the FCM service process.

## Delivery is best-effort

Silent pushes ride FCM data messages, and the OS may throttle, delay, or drop them — for
example under Doze, for backgrounded apps on normal priority, or for force-stopped apps (a
user-force-stopped app receives nothing until it is next launched). Don't build flows that
require guaranteed or timely silent delivery.

## Trying it out

The [sample app](../../samples/kotlin-android-push-app) registers a listener in
`Application.onCreate`, persists every delivery (`SilentPushStore`), and lists them on its
"Silent push log" screen — including pushes that arrived while the app was backgrounded or its
process was dead. It's the reference wiring to copy.
