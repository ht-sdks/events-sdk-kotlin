# Payload reference

This is the on-the-wire FCM payload Hightouch sends and the SDK consumes. You don't
construct it yourself — it's documented for transparency and debugging.

Hightouch pushes are **data-only** FCM messages (no `notification` block), so the SDK fully
controls rendering and engagement tracking. The structured payload is a JSON string under the
`hightouch` key (mirroring the iOS contract).

```jsonc
{
  "token": "<device-token>",
  "data": {
    "hightouch": "<JSON string — see HightouchPushData below>",
    "title": "<rendered title>",
    "body":  "<rendered body>"
  },
  "android": {
    "priority": "HIGH",            // delivery priority -> FCM transport (not display importance)
    "ttl": "259200s",              // message expiry
    "collapse_key": "<optional>"   // FCM collapse key
  }
}
```

## `HightouchPushData`

The JSON-decoded value of `data.hightouch`:

| Field | Type | Notes |
|---|---|---|
| `messageId` | string | **Required.** The SDK drops the push if absent. |
| `attachmentUrl` | string? | Image URL; rendered as `BigPictureStyle`. |
| `defaultAction` | object? | Body-tap action: `{ "type": "openUrl", "data": "<url>" }`. |
| `actionButtons` | array? | Up to 3 action buttons; see [Action button shape](#action-button-shape). |
| `customData` | object? | Marketer-defined string key/value pairs surfaced to your app. |
| `messageContext` | object? | Opaque context round-tripped into engagement events. |
| `notificationChannel` | string? | Per-message channel id (Android). See [channels](notification-channels.md). |
| `groupKey` | string? | `NotificationCompat.setGroup` value. |
| `notificationTag` | string? | Replace/dedupe tag (Android). |
| `sound` | string? | Sound name; honored only on Android 7.x and below (see [behavior](notification-behavior.md#sound)). |

### `action` shape

```jsonc
{ "type": "openUrl", "data": "https://example.com/promo" }
```

`type` is `openUrl` for URLs/deep links, or any custom string routed to your
`customActionHandler`. See [Handlers & deep links](handlers-and-deep-links.md).

### Action button shape

Each entry in `actionButtons`:

```jsonc
{
  "identifier": "archive",          // unique id; reported as action.identifier on the "opened" event
  "title": "Archive",               // button label
  "action": { "type": "..." },      // optional; same shape as `action` above
  "openApp": true,                  // true (default): tap routes through the trampoline so the app can open;
                                    //   false: action runs in a background broadcast receiver, app stays closed
  "requiresUnlock": false           // iOS-only foreground-auth flag; parsed but NOT honored on Android (no effect)
}
```

`openApp` is the only button field that changes Android behavior. `requiresUnlock` is part of the
cross-platform wire contract (it maps to a foreground-authentication requirement on iOS); the
Android SDK parses it but does not yet act on it, so it has no effect here.

## Field mapping summary

| Payload | Rendered as |
|---|---|
| `data.title` / `data.body` | Content title / text |
| `attachmentUrl` | `BigPictureStyle` image (fetched on a background thread) |
| `defaultAction` / `actionButtons` | Tap routing + up to 3 buttons |
| `notificationChannel` | Channel selection (fallback to default if unregistered) |
| `groupKey` | `setGroup` |
| `notificationTag` | `notify(tag, …)` for replace-by-tag |
| `android.priority` | FCM delivery priority (transport only) |
| `android.ttl` / `android.collapse_key` | FCM message expiry / collapse |
