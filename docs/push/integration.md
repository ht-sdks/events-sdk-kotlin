# Integration

## 1. Add the dependency

```groovy
dependencies {
    implementation "com.hightouch.analytics.kotlin:android-push:<version>"
}
```

This pulls in the Hightouch Analytics Android SDK, Firebase Cloud Messaging, and WorkManager
(used to fetch notification images off the FCM thread — auto-initialized, no setup required).

## 2. Set up Firebase

The SDK delivers pushes over FCM, so your app needs Firebase Cloud Messaging configured:

1. Add your app to a Firebase project and download `google-services.json` into the app module.
2. Apply the Google Services Gradle plugin.
3. Provide the matching FCM service-account credentials to Hightouch (server side).

No `FirebaseMessagingService` of your own is required — the SDK registers one (see
[Host-app FirebaseMessagingService](#host-app-firebasemessagingservice) if you already have one).

## 3. Declare a notification icon

A small icon is required to display notifications. Declare it once in your `AndroidManifest.xml`:

```xml
<application>
    <meta-data
        android:name="com.hightouch.push.default_notification_icon"
        android:resource="@drawable/ic_notification" />
</application>
```

The icon is resolved in this order: manifest meta-data (above) →
`HightouchPushConfig.Builder.setSmallIconResId(...)` → your app's launcher icon. If none resolve,
notifications are skipped and a warning is logged.

## 4. Initialize

Call `initialize` once, as early as possible (typically `Application.onCreate`). Two overloads:

```kotlin
// (a) The SDK creates and owns an Analytics instance:
HightouchPush.initialize(
    context = this,
    writeKey = "YOUR_WRITE_KEY",
    config = HightouchPushConfig.Builder("YOUR_APP_ID").build(),
)

// (b) Reuse an Analytics instance your app already has:
HightouchPush.initialize(analytics = existingAnalytics, config = config)
```

See [Handlers & deep links](handlers-and-deep-links.md) for the `HightouchPushConfig` options.

## 5. Token & user lifecycle

| Call | When | Effect |
|---|---|---|
| _(automatic)_ | FCM issues/rotates a token | The SDK persists it and fires a `registered` token event |
| `HightouchPush.identify("user-123")` | User logs in | Associates the token with the user |
| `HightouchPush.logout()` | User logs out | Fires a `disabled` token event and resets analytics |

Read-only state: `HightouchPush.userId`, `HightouchPush.anonymousId`, `HightouchPush.fcmToken`.

## Notification permission (Android 13+)

On Android 13 (API 33) and above, posting notifications requires the runtime
`POST_NOTIFICATIONS` permission. **The SDK does not request it** — your app owns that UX. If it
isn't granted, the SDK silently skips display (it still claims the message). Example:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
}
```

## Host-app FirebaseMessagingService

The SDK registers its own `HightouchFirebaseMessagingService` at `android:priority="-1"`, so if
your app **also** declares a `FirebaseMessagingService`, yours wins. In that case, forward the two
callbacks to the SDK:

```kotlin
class MyFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        HightouchFirebaseMessagingService.handleTokenRefresh(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Returns false if the message isn't a Hightouch push — handle it yourself then.
        if (!HightouchFirebaseMessagingService.handleMessageReceived(this, message)) {
            // ... your own handling ...
        }
    }
}
```
