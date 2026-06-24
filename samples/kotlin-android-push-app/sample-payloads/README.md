# Sample FCM Payloads

Six ready-to-curl payloads exercising every feature of the SDK. All use
[FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/migrate-v1) — substitute
`${FCM_OAUTH_TOKEN}`, `${FCM_PROJECT_ID}`, and the device's FCM `token` before sending. Get the
token from the Status screen in the test app (or `adb logcat` filter `HightouchPushSample`).

Every payload uses an FCM `data`-only shape so behavior is the same in foreground and
background. The Hightouch wrapper is a single JSON-string under the `hightouch` data key.

## Sending a payload

```bash
curl -X POST \
  -H "Authorization: Bearer ${FCM_OAUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  https://fcm.googleapis.com/v1/projects/${FCM_PROJECT_ID}/messages:send \
  -d @01-basic.json
```

| File | What it tests |
|---|---|
| `01-basic.json` | Title + body, no actions. Verifies channel creation, notification post, body-tap engagement event. |
| `02-deep-link.json` | `defaultAction.type=openUrl` with an `https` URL. Verifies urlHandler is called, falls back to `Intent.ACTION_VIEW` on `false`. |
| `03-custom-action.json` | `defaultAction.type=playSound` (non-URL). Verifies customActionHandler is called. |
| `04-action-buttons.json` | Two action buttons (one `openApp=true`, one `openApp=false`). Verifies buttons render, the second routes via the broadcast receiver without launching the activity. |
| `05-rich-media.json` | `attachmentUrl` set to a public image. Verifies BigPictureStyle + that text-only fallback works if the image fetch fails. |
| `06-allowed-scheme.json` | `defaultAction.type=openUrl` with a custom scheme. Requires `allowedProtocols=["hightouchsample"]` in the config (the sample app sets this). |

The payloads assume the test app is registered at applicationId
`com.hightouch.analytics.kotlin.push.sample`.

## Note on the `data` field

FCM only accepts string values for `data` entries, so the JSON inside `hightouch` must be a
single escaped string. The `*.json` files in this directory keep that escape verbatim — copy
them through carefully if you regenerate. The SDK's `HTPushPayload.parse` decodes the string
back into a structured object.
