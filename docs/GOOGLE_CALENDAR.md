# Connecting Google Calendar

Kairo has its own calendar from day one. When you connect Google Calendar, sync works in both directions:

- Events you create in Kairo (by voice, in the app or in the dashboard) are uploaded to your Google calendar.
- Changes made in Google (from your phone, Gmail and so on) arrive in Kairo every 5 minutes, or when you choose
  "Sync now".
- The synced window is 30 days back and 180 days ahead.

Other languages: [Spanish version](es/GOOGLE_CALENDAR.md).

## 1. Create the credentials in Google Cloud (once, about 10 minutes)

1. Go to <https://console.cloud.google.com/> and create a project (for example "Kairo").
2. Open **APIs & Services > Library**, search for **Google Calendar API** and click **Enable**.
3. Open **APIs & Services > OAuth consent screen** (Google Auth Platform):
   - User type: **External**.
   - App name: Kairo. Support email: your own.
   - Scopes: add `https://www.googleapis.com/auth/calendar.events` and
     `https://www.googleapis.com/auth/calendar.calendarlist.readonly`.
4. **Important: publish the app** (Publishing status > **Publish app / In production**). While the app is in
   *Testing* mode, Google revokes the permission every **7 days** and you would have to reconnect. For personal use
   you do not need Google verification. When you connect, you will see a "Google hasn't verified this app" warning.
   Click *Advanced > Go to Kairo*.
5. Open **APIs & Services > Credentials > Create credentials > OAuth client ID**:
   - Application type: **Web application**.
   - Authorized redirect URI: `https://kairo.example.com/api/integrations/google/callback`
     (the dashboard shows the exact URI under *Settings > Google Calendar*).
6. Copy the **Client ID** and the **Client secret**.

## 2. Configure the server

In your `.env`:

```env
GOOGLE_CLIENT_ID=1234567890-xxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-xxxx
KAIRO_PUBLIC_URL=https://kairo.example.com
```

Restart with `docker compose up -d`.

## 3. Connect

In the dashboard, go to **Settings > Google Calendar > Connect**. Accept the permissions and return to Kairo.
You can choose which calendar to use (the primary one by default) and disconnect whenever you want.

The connection runs from the dashboard and finishes in **the same browser** where you clicked *Connect*, within 10
minutes at most. Kairo leaves a temporary cookie in that browser, and the return from Google is only valid with
it. If you open the Google link in another browser or on another device, you will see "Invalid OAuth state". Click
*Connect* again. The Android and iPhone apps use the connection you make from the dashboard.

## Security

- The Google refresh token is stored **encrypted** (AES-256-GCM with `KAIRO_ENCRYPTION_KEY`).
- Kairo only asks for access to events and the list of calendars. It does not read your email or your contacts.
- The return from Google (`state`) is signed and tied to an HttpOnly cookie in the browser that started the
  connection. It works only once. Nobody can connect their Google account to yours (or yours to theirs) by sending
  you a link.
- When you disconnect, Kairo revokes the token at Google and deletes it.

## Details

- Events that come from Google are marked with a sync icon.
- If you edit an event in Kairo and in Google at the same time, the pending local change wins. It is sent on the
  next sync.
- Recurring events created in Kairo are uploaded with their rule (RRULE). Recurring events from Google arrive as
  individual occurrences inside the window.
