# Hightouch Android Push

`android-push` adds Hightouch push-notification support to the Hightouch Analytics
Kotlin/Android SDK: FCM token registration, notification rendering (image, action buttons,
channels), tap handling with deep-link/custom-action callbacks, and engagement tracking.

- **Artifact:** `com.hightouch.analytics.kotlin:android-push`
- **Min SDK:** 21
- **Transport:** Firebase Cloud Messaging (FCM HTTP v1)

## Quick start

1. **Add the dependency** and your Firebase config — see [Integration](integration.md).
2. **Declare a notification icon** in your manifest:
   ```xml
   <meta-data
       android:name="com.hightouch.push.default_notification_icon"
       android:resource="@drawable/ic_notification" />
   ```
3. **Initialize** once at app startup:
   ```kotlin
   HightouchPush.initialize(
       context = this,
       writeKey = "YOUR_WRITE_KEY",
       config = HightouchPushConfig.Builder("YOUR_APP_ID")
           .setUrlHandler { url, _ -> myRouter.handle(url) }
           .build(),
   )
   ```
4. **Identify** the user on login and **log out** on sign-out:
   ```kotlin
   HightouchPush.identify("user-123")
   // ...
   HightouchPush.logout()
   ```
5. **Request the notification permission** on Android 13+ (the SDK never requests it for you):
   see [Integration → Notification permission](integration.md#notification-permission-android-13).

That's it — the SDK auto-registers its `FirebaseMessagingService`, captures the FCM token, and
renders incoming Hightouch pushes.

## Documentation

| Doc | Covers |
|---|---|
| [Integration](integration.md) | Gradle, Firebase, manifest, `initialize`, token lifecycle, permission, host-app FMS interop |
| [Notification channels](notification-channels.md) | The default channel, defining your own, per-message channel selection, importance |
| [Notification behavior](notification-behavior.md) | Title/body, image, action buttons, sound, delivery priority, replace/tag, grouping |
| [Handlers & deep links](handlers-and-deep-links.md) | `urlHandler`, `customActionHandler`, allowed schemes, open-app behavior, `customData` |
| [Payload reference](payload-reference.md) | The on-the-wire FCM payload contract |
