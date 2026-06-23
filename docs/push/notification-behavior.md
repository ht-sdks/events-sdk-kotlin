# Notification behavior

How each campaign field maps to the rendered Android notification.

## Title & body

Rendered as the notification's content title and text. For Hightouch pushes (which are data-only
messages) they arrive in the payload and the SDK applies them directly.

## Image

If the campaign includes an image, the SDK fetches it and renders a `BigPictureStyle` notification
(large image when expanded, thumbnail when collapsed). The fetch runs on a **WorkManager background
thread**, not FCM's delivery thread, so a slow image host can't cause the message to be dropped. If
the fetch fails, the notification still posts without the image.

## Click behavior & action buttons

Tapping the body or an action button can open the app, a deep link, or a web URL, and fires an
`opened` engagement event. Buttons can be foreground (open the app) or background (run without
opening the app). This is covered in detail in
[Handlers & deep links](handlers-and-deep-links.md). Up to three action buttons are shown.

## Sound

On **Android 8.0+ sound is a property of the channel**, set when the channel is created and not
changeable per message. To play a custom sound, create a channel configured with that sound and
target it from the campaign — see [Notification channels](notification-channels.md). A per-message
sound field is honored **only on Android 7.x and below**, where the named sound is looked up in
`res/raw/` (bundle the file in your app); if it isn't found, the default sound is used.

## Delivery priority vs. on-screen importance

These are two independent axes — don't confuse them:

- **Delivery priority** (the campaign's normal/high setting) maps to FCM's message priority. It
  controls *when* the device wakes to receive the push (immediately vs. batched for battery). It
  does **not** affect how the notification looks.
- **On-screen importance** (heads-up banner vs. silent) is owned by the **channel**. To make pushes
  more or less prominent, target a channel with the importance you want.

## Replace an existing notification (tag)

If a campaign sets a **replace/notification id**, the SDK posts with that tag so a later push using
the same tag *replaces* the earlier one instead of stacking (useful for live scores, order status,
etc.). Without a tag, each push is identified by its message id.

## Grouping

If a campaign sets a **group key**, the notification is grouped with others sharing that key
(`NotificationCompat.setGroup`). The SDK does not post a separate group-summary notification.

## Icon & accent color

- **Small icon:** required; see [Integration → Declare a notification icon](integration.md#3-declare-a-notification-icon).
- **Accent color:** optional, via `HightouchPushConfig.Builder.setNotificationColorResId(...)`.
