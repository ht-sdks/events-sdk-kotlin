# Notification channels

On Android 8.0 (API 26) and above, every notification belongs to a **channel**, and the channel —
not the individual notification — owns how it presents: importance (heads-up banner vs. silent),
sound, vibration, and lights. The user can override any of these per channel in system settings.
Once a channel is created, its importance and sound are fixed.

## The default channel

If a push doesn't name a channel and you haven't configured one, the SDK posts on a channel it
creates automatically:

- **id:** `hightouch_default`
- **name:** "Push notifications"
- **importance:** `IMPORTANCE_DEFAULT` (makes a sound and appears in the shade/status bar; no
  intrusive heads-up banner)

This gives you working push out of the box with no channel setup.

### Renaming the default channel

You can point the SDK's default at a different id:

```kotlin
HightouchPushConfig.Builder("APP_ID")
    .setNotificationChannelId("marketing")
    .build()
```

If a channel with that id doesn't exist yet, the SDK creates it at `IMPORTANCE_DEFAULT`. If you
**already created** a channel with that id (see below), the SDK leaves it untouched — your
importance, sound, and other settings are preserved.

## Defining your own channels

To control importance, sound, or grouping, create channels in your app and let pushes target them.
Because importance is fixed at creation, this is the **only** way to make a push present
differently (e.g. as a heads-up banner, or silently):

```kotlin
val channel = NotificationChannel(
    "orders",
    "Order updates",
    NotificationManager.IMPORTANCE_HIGH, // heads-up banner
).apply {
    setSound(orderSoundUri, audioAttributes)
}
context.getSystemService(NotificationManager::class.java)
    .createNotificationChannel(channel)
```

Create your channels early (e.g. in `Application.onCreate`) so they exist before a push arrives.

## Per-message channel selection

A campaign can specify which channel a push targets (the **Notification Channel** field). The SDK
resolves the channel like this:

1. The channel named in the push payload, **if your app has registered it**.
2. Otherwise the configured default (`setNotificationChannelId`, if set).
3. Otherwise `hightouch_default`.

If the payload names a channel your app never created, the SDK **falls back to the default
channel** and logs a warning (posting to a non-existent channel would otherwise silently fail on
API 26+). The SDK only ever auto-creates the default channel — any channel you want a campaign to
target must be defined in your app.

## Older Android versions

Below API 26 there are no channels; importance is expressed as a notification *priority*. The SDK
posts at `PRIORITY_DEFAULT` there, matching the default channel's importance.
