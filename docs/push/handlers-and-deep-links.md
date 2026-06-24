# Handlers & deep links

When a notification is tapped, the SDK fires an `opened` engagement event and then routes the
action. You wire up routing through `HightouchPushConfig`.

## Configuration

```kotlin
val config = HightouchPushConfig.Builder("APP_ID")
    .setUrlHandler { url, context -> myRouter.navigate(url) }          // return true if handled
    .setCustomActionHandler { action, context -> handle(action) }      // return true if handled
    .setAllowedProtocols(listOf("myapp"))                              // extra schemes to allow
    .setAutoLaunchApp(true)                                            // default true
    .setNotificationChannelId("marketing")                             // optional, see channels doc
    .setSmallIconResId(R.drawable.ic_notification)                     // optional
    .setNotificationColorResId(R.color.brand)                          // optional
    .build()
```

Both handlers are `fun interface`s, so Kotlin callers can pass lambdas and Java callers can pass
functional instances.

## How a tap is routed

1. **`openUrl` actions** (deep links and web URLs): the SDK calls your `urlHandler`. If it returns
   `false` (or none is set), the SDK falls back to `Intent.ACTION_VIEW` — but only for `https` and
   any schemes you listed in `setAllowedProtocols`. Other schemes are dropped with a warning.
2. **Other action types**: handed to your `customActionHandler` as a `HightouchAction(type, data)`.
3. **Open-app** (see below): if nothing above opened anything, the host app is launched.

`https` is always allowed; add custom schemes (e.g. `"myapp"`, `"tel"`) via `setAllowedProtocols`.

## Threading

Both handlers are invoked **synchronously on the main thread** (whether the tap arrives via the
trampoline activity or a background action button). Keep them fast and non-blocking — start an
activity, post to your own queue, or hand off to a coroutine/executor, but don't do network or disk
I/O inline, or you risk an ANR. Engagement-event tracking is already dispatched off-thread by the
SDK, so you don't need to background that yourself.

## Open-app behavior

Tapping a notification body — or a foreground (open-app) action button — should bring the user into
your app. If the tap didn't already open something (no deep link, or the deep link wasn't handled),
the SDK launches your app's launcher activity.

- This is gated by `setAutoLaunchApp(...)`, which **defaults to `true`**. Set it to `false` if your
  app drives all navigation itself and you never want the SDK to start the launcher.
- Background (`openApp=false`) action buttons never launch the app.

## Action context & `customData`

Handlers receive a `HightouchActionContext`:

```kotlin
data class HightouchActionContext(
    val source: HightouchActionSource,      // Push (body tap) or ActionButton(identifier)
    val customData: Map<String, String>?,   // campaign-defined key/value pairs, if any
)
```

Use `source` to tell a body tap from a specific button, and `customData` to branch on
campaign-specific values.

### Reading `customData` when the app is opened

For the plain open-app case (no handler runs), the SDK attaches `customData` to the launch intent.
Read it from your launched activity:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val data: Map<String, String>? = HightouchPush.getCustomData(intent)
    // route based on data...
}
```

(Also handle it in `onNewIntent` if your launch activity is `singleTop`/`singleTask`.)
