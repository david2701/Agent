# Kairo for Android

A native app written in Kotlin 2.4 with Jetpack Compose and **Material 3 Expressive**. It runs on regular phones,
foldables (Galaxy Z Fold/Flip, Pixel Fold) and tablets, and includes a **Wear OS** app.

Other languages: [Spanish version](es/ANDROID.md). The app follows your phone's language: Spanish if the phone is set
to Spanish, English otherwise.

## Install

1. Download the APK from the CI artifacts (*Actions > CI > kairo-apk*), or build it yourself:
   ```bash
   cd android
   ./gradlew :app:assembleRelease      # phone: app/build/outputs/apk/release/app-release.apk
   ./gradlew :wear:assembleRelease     # watch: wear/build/outputs/apk/release/wear-release.apk
   ```
   You need JDK 21 and the Android SDK (platform 37). Without your own keystore, the release build is signed with
   the debug key. That is fine for testing, but install the family's apps with your own signature (see below).
2. Install it on the phone (`adb install app-release.apk`, or open the file).
3. Open Kairo and enter your server URL (`https://kairo.example.com`), your email and your password.
   If you were invited to a family (or someone gave you an access code), tap **I have an invite code**.

### Release signing and your server

Before you install Kairo on your family's phones, sign it with your own key. Android only accepts updates signed
with the same key. If you change keys later, you have to uninstall and reinstall, and you lose the session. Keep the
file and the passwords somewhere safe. Without them you cannot publish updates.

```bash
keytool -genkeypair -v -keystore kairo-release.jks -alias kairo -keyalg RSA -keysize 4096 -validity 10000
```

Create `android/keystore.properties` (it is in `.gitignore` and never gets committed):

```properties
storeFile=/path/to/kairo-release.jks
storePassword=your-password
keyAlias=kairo
keyPassword=your-password
```

Alternatively, use the variables `KAIRO_KEYSTORE_FILE`, `KAIRO_KEYSTORE_PASSWORD`, `KAIRO_KEY_ALIAS` and
`KAIRO_KEY_PASSWORD`. The phone and the watch use the same key. For CI to publish signed APKs, add the secrets
`ANDROID_KEYSTORE_BASE64` (`base64 -w0 kairo-release.jks`) and `ANDROID_KEYSTORE_PASSWORD` in GitHub
(*Settings > Secrets and variables > Actions*).

**Your server**: the watch pairs against the address it was built with, and the phone shows that address when you
sign in. Build with `KAIRO_SERVER_URL=https://kairo.example.com ./gradlew assembleRelease`. You can also set
`kairo.serverUrl=` in `android/gradle.properties`, or the repository variable `KAIRO_SERVER_URL` for CI.

## Recommended first steps (Settings)

| Setting | What it does |
|---|---|
| **Hey Kairo** | Continuous listening for the wake phrase ("Hey Kairo" in English, "Oye Kairo" in Spanish). It downloads an offline speech model once (40 MB). |
| **System assistant** | Choose Kairo as the *default digital assistant*, then long-press the home or power button to talk to it. |
| **Unrestricted battery** | Samsung and other manufacturers kill background apps. Required for continuous listening. |
| **Exact alarms** | Reminders ring at the exact time, even with no internet. |
| **"All the time" location** | Optional. Needed for alerts when you arrive at or leave a saved place. |
| **Pair watch** | Generates a 6-digit code to sign in on Wear OS. |
| **Instant alerts without Firebase** | Connects Kairo to the ntfy app (UnifiedPush) so notifications arrive with the app closed. |
| **Family** | Members, invitations and household notices (also under *More > Family*). |

Other ways in: a **Quick Settings tile** called "Kairo", a **widget** with your day and a talk button, a
**shortcut** when you long-press the icon, and **Share > Save to Kairo** from any app (text, links or photos). Kairo
reads the text in an image and creates notes, reminders or events.

## How "Hey Kairo" works

- A foreground service (microphone type) listens with **Vosk**, inside the phone. The audio **never leaves the
  device** until it detects the wake phrase. The wake phrase is "Hey Kairo" in English and "Oye Kairo" in Spanish.
  Either one wakes the app, and the name shown in the app follows the app language.
- When it hears the phrase, it plays a tone, Kairo records what you say and detects when you stop talking.
- The recording goes to your server. Whisper (Nan) transcribes it, the agent acts, and Kokoro (Nan) answers with
  voice. Speech recognition and replies use your language setting (English, Spanish, or auto-detect).
- If Kairo asks you a question, it keeps listening without you repeating the phrase (you can turn this off).

Android rules you should know:
- The "Kairo is listening" notification and the green microphone dot are mandatory.
- Since Android 14, listening cannot restart on its own after a reboot. Kairo shows a notification. Tap it (or open
  the app) and it goes back to listening.
- The system's hardware voice detection (the one behind "Ok Google") is reserved for system apps. That is why Kairo
  uses its own detector.

## Family

A household with several profiles. Everyone has their own items and shares what they mark as **shared**: events,
lists, tasks, notes and places. Personal items are private, including from the administrator.

- **Join**: on the sign-in screen, *I have an invite code* checks the code (`XXXX-XXXX`; case, spaces and the dash
  do not matter) and shows "You were invited to the ... family". Then enter your name (already filled in), email and a
  password of at least 10 characters. With an **access code** (forgotten password), it only asks for the new
  password and signs out your other sessions.
- **Family screen** (*More* or *Settings*): household name, members with their role and "You", pending invitations,
  "Notify the family" (a message that arrives as a notification) and **Updates** with "Mark all as read".
  Administrators invite people (name and role), see the code in large type with **Share invitation** (Android's
  share sheet with the link and the code), revoke invitations, generate access codes, change roles and remove
  members. Removing a member deletes their account and personal items, and what they shared passes to the person who
  removed them.
- **On each screen**: Notes (filter All, Mine, Family, and a "Family" or "Sam's" badge), Tasks (Family,
  **Assignee** with initials on the card and the board, an "Assigned to me" filter), Lists, Calendar ("Family event"
  when you create or edit, and "scheduled by"), Reminders (**For**: you or another member; "For X" or "From X") and
  Places (under Reminders). Only the person who created an item can stop sharing it.
- **Today** shows a "Family" card with the latest unread notices. It does not appear if you are alone. For
  administrators, it suggests inviting someone.
- Family data is cached so names show instantly. It reloads when you open the app, when you sign in, and when the
  server announces a change (`entity.changed: family`).

## Notifications without Firebase

Kairo uses neither Firebase nor Google services to notify you. There are three levels and they work together. Each
notification has an id, and the phone never shows it twice, whether it arrived through push, WebSocket, a local
alarm or sync.

| Level | When it arrives | What you need |
|---|---|---|
| **1. Self-hosted push (UnifiedPush)** | Instantly, with Kairo closed and no extra battery use | The **ntfy** app (F-Droid or Play Store) pointed at your ntfy server (the `push` profile in `docker-compose.yml`) |
| **2. Direct connection or "Hey Kairo"** | Instantly while the service runs | Turn on *Direct connection to your server* (or listening). It uses a bit more battery. |
| **3. Local alarms and sync** | Reminders, event alerts and the briefing at their time even with no internet. Everything else on the next sync (15 min). | Nothing |

1. **Self-hosted push**: in *Settings > Instant alerts without Firebase*, tap **Set up**. Kairo uses the installed
   distributor (ntfy) and asks it for an endpoint, using your server's VAPID key. The endpoint and the encryption keys
   (`p256dh`, `auth`) are registered with `POST /api/push/register`. The server encrypts each notification for this
   phone (RFC 8291) and the connector decrypts it. The content is the same `{type, data}` as the WebSocket and goes
   through the same handler. **Send test** tells you how many devices accepted the notification. The registration is
   renewed when you open the app and when you sign in, and deleted when you sign out. Server details are in
   [PUSH.md](PUSH.md).
2. **Direct connection**: a light foreground service (type `specialUse`) that only keeps the WebSocket open, with the
   permanent notification "Kairo connected". It never starts on its own. You turn it on, and it comes back after a
   reboot. The "Hey Kairo" service also keeps this connection, and so does the open app.
3. **Exact local alarms** for reminders (including recurring ones and ones another member scheduled for you, shown
   as "From Sam") and event alerts, plus **sync** with WorkManager. `/sync/device` also returns the unread
   family notices from the last 48 hours, and the phone shows the ones it has not seen yet.

Notification channels: Reminders, Family (messages, assigned tasks and changes to shared items), Daily briefing,
Direct connection, Continuous listening, Recordings and Timers. Tapping a family notice opens the related screen (or
Family) and marks it as read.

Location reminders use the system's proximity alerts (no Google Play Services). In addition, the server can publish
to an **ntfy** topic (`NTFY_URL`, `NTFY_TOPIC`) as an extra channel.

## Foldables

| Posture | Layout |
|---|---|
| Phone or closed Fold | Bottom bar. Lists and details on separate screens. |
| Open Fold (portrait) | Side rail. Today in two columns. Notes, Chat and Lists with list and detail side by side. Family with members on the left and notices on the right. |
| Open Fold (landscape) / tablet | Tasks as a 3-column board. Month calendar with an agenda. |
| Tabletop mode (half-open) | The orb and the answer above the fold. Keyboard and microphone below. |

Nothing is lost when you fold or unfold the device. State lives in ViewModels.

## Screenshots

The screenshots are generated without an emulator, using Roborazzi against a local backend:

```bash
cd android
./gradlew :app:recordRoborazziDebug     # output goes to docs/screenshots/android
```

It uses the backend at `KAIRO_TEST_SERVER` (default `http://localhost:8088`) with a single sign-in per run. Times
are shown in `America/Bogota` (change this with `KAIRO_TEST_TZ`). The family screenshots create a temporary invitation
and revoke it when they finish.

## Privacy and security on the phone

- The session (refresh token) is stored encrypted with a key from the **Android Keystore**.
- HTTPS is required (HTTP is only allowed for emulator testing).
- App data is excluded from automatic cloud backups.
- Meeting recordings are uploaded in segments and deleted from the phone once uploaded.

## Wear OS

A standalone app (it works over the watch's Wi-Fi or LTE). Pair it with the 6-digit code from
*Settings > Pair watch* on the phone. Tap the button to talk. Kairo answers in text and, if the watch has a speaker,
with voice. Notifications from the phone arrive on the watch.
