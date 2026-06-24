# Android Setup — Hightouch Push Test App

This is the Android counterpart to the iOS test app's `SIGNING.md`. Instead of Apple Developer
certificates / provisioning profiles, Android push requires a Firebase Cloud Messaging (FCM)
project plus an app-signing keystore.

## 1. Firebase Cloud Messaging

The repo ships a **placeholder** `google-services.json` so the Gradle build succeeds without
secrets. The placeholder does **not** deliver real notifications — replace it before testing on
a device.

To get a real `google-services.json`:

1. Open the [Firebase console](https://console.firebase.google.com/).
2. Create a project (or open an existing one for the Hightouch sample apps).
3. Add an Android app with package name **`com.hightouch.analytics.kotlin.push.sample`**.
4. Download the generated `google-services.json` and replace this directory's file with it.
5. In the same Firebase project, go to *Project Settings → Cloud Messaging* and copy the **HTTP
   v1 API** server credentials. You'll use them to send test pushes from your terminal.

> The `google-services` Gradle plugin validates the JSON's structure on every build. If you see
> `Could not parse google-services.json`, you've corrupted the file — re-download from Firebase.

## 2. Local credentials

The app reads three values at build time from a gitignored `local.properties` at the repo
root. Add (or extend) the file with:

```properties
hightouch.writeKey=<your-hightouch-write-key>
hightouch.appId=<your-push-app-uuid>
hightouch.apiHost=<optional-api-host-override>
```

If left blank, `MainApplication.onCreate` logs a warning and skips SDK initialization. Useful
for verifying the build without leaking real credentials.

## 3. Signing the APK

For day-to-day development, Android Studio uses its default debug keystore
(`~/.android/debug.keystore`) — no setup needed.

For release builds (or sharing test APKs), use a project keystore:

1. Generate or obtain a `.jks` keystore. **Do not commit it.**
2. Add to `local.properties`:
   ```properties
   keystore.path=/absolute/path/to/release.jks
   keystore.password=<password>
   keystore.alias=<alias>
   keystore.aliasPassword=<password>
   ```
3. Extend `app/build.gradle` with a `signingConfig` reading those properties — left as an
   exercise so each team can use their preferred keystore-management workflow.

## 4. Sending a test push

Once your real `google-services.json` is in place and the app has registered, the FCM token
will be logged on app launch (filter logcat for `HightouchPushSample`). To send a test push
from a terminal, use [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/migrate-v1)
with your project's OAuth bearer token:

```bash
curl -X POST \
  -H "Authorization: Bearer ${FCM_OAUTH_TOKEN}" \
  -H "Content-Type: application/json" \
  https://fcm.googleapis.com/v1/projects/${FCM_PROJECT_ID}/messages:send \
  -d '{
    "message": {
      "token": "<your-fcm-token>",
      "data": {
        "title": "Hello",
        "body": "From terminal",
        "hightouch": "{\"messageId\":\"test-1\"}"
      }
    }
  }'
```

The `sample-payloads/` directory next to this file has curl-ready examples for every payload
shape — basic, deep-link, custom-action, action-buttons, rich-media, and allowed-scheme. See
`sample-payloads/README.md` for what each one exercises and how to send it.
