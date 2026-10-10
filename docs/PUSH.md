# Notifications without Firebase

Kairo has its own notification system and does not depend on Firebase or Google Play Services. It works on three
levels. The higher the level, the faster notifications arrive while the app is closed.

Other languages: [Spanish version](es/PUSH.md).

| Level | How it arrives | What it needs |
|---|---|---|
| **1. Self-hosted push (UnifiedPush)** | Instantly, with the app closed and no extra battery use | An ntfy server (included in `docker-compose.yml`) and the ntfy app on each phone |
| **2. Direct connection** | Instantly while the app is open, "Hey Kairo" is active, or you turn on *Direct connection to your server* | Nothing else. It uses a little more battery. |
| **3. Local alarms and sync** | Reminders, event alerts and the morning briefing ring on time even with no internet. Everything else arrives on the next sync (every 15 minutes). | Nothing |

All three work together. The phone recognizes each notification by its id and never shows it twice.

**iPhone** has no UnifiedPush. Level 1 is Apple's push service (APNs), which is also optional.
See [Apple push (APNs) for iPhone](#apple-push-apns-for-iphone).

## How self-hosted push works

```
Kairo (your server) --encrypted Web Push--> ntfy (your server) --phone connection--> ntfy app --> Kairo on the phone
```

- **UnifiedPush** is an open standard that lets apps receive push notifications without Firebase. The ntfy app acts
  as the "distributor". It keeps a single connection to your ntfy server and hands messages to Kairo.
- Kairo encrypts each message for the phone (RFC 8291, `aes128gcm`) and signs it with VAPID (RFC 8292). ntfy only sees
  encrypted bytes. Neither ntfy nor anyone on the way can read your reminders.
- The VAPID key is generated automatically on first use and stored encrypted with `KAIRO_ENCRYPTION_KEY`.

## Set it up

1. On the server, set the public ntfy URL in `.env` and start it:
   ```bash
   # .env
   NTFY_PUBLIC_URL=https://ntfy.example.com
   ```
   ```bash
   docker compose --profile push up -d
   ```
2. Add the `ntfy.example.com` block from [`deploy/Caddyfile.example`](../deploy/Caddyfile.example) to your Caddyfile
   and run `caddy reload`.
3. On each family phone:
   - Install **ntfy** (F-Droid or Play Store).
   - In ntfy, set *Settings > Default server* to `https://ntfy.example.com`.
   - In Kairo, open *Settings > Instant notices, no Firebase > Set up*, choose ntfy and tap *Send test*.

You do not need to create topics or subscriptions by hand. Kairo and ntfy manage them.

## ntfy server security

- UnifiedPush topics are random and impossible to guess, and the content is encrypted.
- The bundled configuration does not allow sign-ups (`NTFY_ENABLE_SIGNUP=false`), keeps messages for 24 hours and
  listens on `127.0.0.1` only (Caddy publishes it over HTTPS).
- To lock it down further, ntfy supports access control (`auth-default-access: deny-all`) with anonymous write
  access only on the `up*` topics. See the ntfy documentation on UnifiedPush.

### Which endpoints Kairo accepts

The phone sends the endpoint, so Kairo validates it (when it is registered and before every delivery). This stops
anyone from using it to reach services on your network:
- It must be `https`. `KAIRO_PUSH_ALLOW_HTTP=true` allows `http`, for testing.
- The host of `NTFY_PUBLIC_URL` (or `NTFY_URL`) is always accepted, even if it resolves to an IP on your local
  network, because it is your own ntfy. If that host is local or listed in `NO_PROXY`, Kairo reaches it without
  going through `HTTPS_PROXY`.
- Any other host (ntfy.sh, another distributor) must resolve to public addresses. If you use a distributor on your
  network under a different name, set `KAIRO_PUSH_ALLOW_PRIVATE=true`. This accepts private networks, but never
  loopback or `169.254.x.x`.
- An endpoint or APNs token that another account has already registered is not reassigned (`400`). Sign out of that
  account on the phone, or revoke its device in *Settings > Devices*, before signing in with another one.

## Check that it works

- Dashboard: *Settings > Notifications* shows how many phones have push registered.
- App: *Send test* sends a notification through push only. If it arrives with the app closed, you are all set.
- API: `POST /api/push/test` returns `{delivered}` (see [API.md](API.md#self-hosted-push-no-firebase)).

## Apple push (APNs) for iPhone

With the iOS app closed, the only push that can arrive is Apple's. It is **optional**. Without APNs, the app still
works with its local notifications (reminders, event alerts and the morning briefing are scheduled on the iPhone and
ring even with no internet), the direct connection while it is open, and background refresh. The only thing you lose
is instant delivery of family notices while the app is closed.

```
Kairo (your server) --HTTP/2 + .p8 key token--> APNs (Apple) --> iPhone (and the paired Apple Watch)
```

You need an Apple Developer Program account, the same one you use to sign the app (see [IOS.md](IOS.md)).

### 1. Create the `.p8` key

1. At [developer.apple.com](https://developer.apple.com/account), go to *Certificates, Identifiers & Profiles > Keys*
   and click **+**.
2. Give it a name (for example "Kairo push"), check **Apple Push Notifications service (APNs)** and register the
   key. If Apple asks you to configure it, choose the *Sandbox & Production* environment so one key works for both.
3. Download the `AuthKey_XXXXXXXXXX.p8` file. **You can only download it once.** Store it somewhere safe and never
   commit it to the repository (`*.p8` and `secrets/` are in `.gitignore`).
4. Note the **Key ID** (10 characters, also in the file name) and your **Team ID** (under *Membership details*).

The app's App ID must have the *Push Notifications* capability. With Xcode's automatic signing this is turned on for
you, because the app already includes the `aps-environment` entitlement.

### 2. Configure the server

```bash
# .env
APNS_KEY_ID=ABC123DEFG
APNS_TEAM_ID=TEAM123456
APNS_TOPIC=app.kairo.ios                    # KAIRO_BUNDLE_ID from ios/Config/Signing.xcconfig
APNS_PRIVATE_KEY_FILE=/run/secrets/apns.p8
```

With Docker, mount the file into the container (uncomment these lines in the `kairo` service of
`docker-compose.yml`):

```yaml
    volumes:
      - ./secrets/AuthKey_ABC123DEFG.p8:/run/secrets/apns.p8:ro
```

If you prefer not to use a file, put the key contents in `APNS_PRIVATE_KEY`, on a single line, with `\n` in place of
each line break:

```bash
awk 'NF {printf "%s\\n", $0}' AuthKey_ABC123DEFG.p8
```

Restart with `docker compose up -d`. The log says that APNs is enabled for your topic. If a value is missing or the
key is not valid, it says that APNs is disabled, and Kairo keeps working without Apple push.

### Sandbox and production

| How you installed the app | Environment | Apple server |
|---|---|---|
| From Xcode (debug build) | `sandbox` | `api.sandbox.push.apple.com` |
| TestFlight or the App Store | `production` | `api.push.apple.com` |

When it registers, the app says which environment it is in, and the server uses the right one for each token. The
same `.p8` key works for both. If a token does not match its environment or the app, Apple answers `BadDeviceToken`
or `DeviceTokenNotForTopic` and Kairo deletes it. The app registers it again when you sign in.

### Check that it works

- App: *Settings > Notifications* says whether the server has Apple push enabled and how many devices you have
  registered.
- API: `GET /api/push/apns` returns `{available: true, registeredDevices: 1}` (see
  [API.md](API.md#apple-push-apns)).
- Close the app and tap *Send test* from another device, or call `POST /api/push/test`. `delivered` also counts the
  iPhones that accepted the notification.

### Good to know

- Reminders and event alerts arrive as *time-sensitive*, with "Done" and "In 10 min" buttons on reminders. Each
  notification carries the same id as the app's local alarm, so it is never duplicated.
- Unlike Web Push, APNs content is not end-to-end encrypted. It travels over TLS to Apple, who can see the title and
  text of the notification.
- Tokens are deleted when you sign out, when you revoke the device in *Settings > Devices*, and when Apple reports
  that they are no longer valid.

## ntfy as an extra channel (optional)

Besides the app's push, the server can publish notifications to an ntfy topic, for people who prefer to see them in
the ntfy app itself or on the desktop. Set `NTFY_URL`, `NTFY_TOPIC` and `NTFY_TOKEN` in `.env` (the owner's topic),
or use one topic per person in *Settings*.

`NTFY_TOPIC` and `NTFY_TOKEN` belong to the owner (`KAIRO_OWNER_EMAIL`). Only the owner's notifications are
published with the token. Other members can pick their own topic (letters, numbers, `-` and `_`), which Kairo
publishes **without** a token, and they cannot use the owner's topic. If your ntfy requires authentication to write
(`auth-default-access: deny-all`), grant anonymous write access to the members' topics
(`ntfy access everyone sofia-topic write-only`), or have them use topics on ntfy.sh.
