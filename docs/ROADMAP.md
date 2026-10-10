# Roadmap

This is a short and honest picture of where Kairo stands and where it could go. It is not a promise or a schedule.
Kairo is a small project, so priorities can change.

## Done

- **Backend**: Kotlin and Ktor, PostgreSQL with pgvector, an agent with more than 30 tools, real-time updates over
  WebSocket and SSE, schedulers, Google Calendar sync and MCP client.
- **Dashboard**: a React web app served by the backend.
- **Android and Wear OS**: native apps with offline "Hey Kairo" detection, exact local alarms, foldable layouts and a
  standalone watch app.
- **iOS, iPadOS and watchOS**: native SwiftUI apps with widgets, Live Activities, Siri and Shortcuts, AlarmKit
  timers, the share extension and an Apple Watch app.
  They have been built on CI and tested in the simulator, but not yet on physical devices (see [IOS.md](IOS.md#known-limitations)).
- **Family**: one server for a household, personal and shared items, assignees, family notices, invitations and
  access codes.
- **Push without Firebase**: encrypted UnifiedPush through a self-hosted ntfy, plus optional Apple push (APNs).
  See [PUSH.md](PUSH.md).
- **Backups**: a daily full backup with retention, a restore script and a marker file for monitoring.
- **Security hardening**: protection against hidden instructions, SSRF protection, session and sign-in hardening,
  per-person AI rate limits. See [SECURITY.md](../SECURITY.md).
- **English and Spanish**: every app, the dashboard and the server's own text are available in both languages.
  English is the default, and each app follows the device or browser language.

## Next and ideas

These are ideas, ordered roughly by how likely they are to happen. None of them has a date.

### macOS app (planned, not started, not in this release)

A native SwiftUI app that shares `KairoKit` with the iOS app. The idea:

- a menu bar item and a global hotkey to talk to Kairo from anywhere;
- Spotlight and App Intents integration;
- the same account, server and features as the other apps.

Today, on a Mac you can use the dashboard in a browser. Nothing of the macOS app exists yet, so **it is not part of
this release**.

### More languages

Contributions are welcome. English and Spanish are supported today. Adding a language touches each platform
(see also [CONTRIBUTING.md](../CONTRIBUTING.md#adding-a-language)):

| Part | Where to add the language |
|---|---|
| Dashboard | A new file `dashboard/src/lib/i18n/<lang>.ts` |
| Android and Wear OS | A new `values-<lang>/strings.xml` |
| iOS and watchOS | A new language in `Localizable.xcstrings` |
| Backend | A new language in the `Messages` catalog (assistant text, notices, briefing, weather, dates) and in the allowed values of `settings.language` |

### Real-device test coverage

More testing on physical devices: iPhone, iPad, Apple Watch, foldables, and a range of Android manufacturers with
aggressive battery management. The checklist in [DEPLOYMENT.md](DEPLOYMENT.md#10-device-test-checklist) is the
starting point.

### Easier distribution

TestFlight builds for iOS and an F-Droid listing for Android, so people do not have to build the apps themselves.
Each person would still point the app at their own server.

### Plugin and tool SDK

A documented way to add custom agent tools without forking the backend. MCP servers already cover much of this
today, so the SDK would focus on tools that need to run inside the server.

### Optional local models

The option to run speech, embeddings or chat models locally instead of using Nan, for people who want nothing to
leave their network.

## How to help

Open an issue to discuss an idea before you start on something big. See [CONTRIBUTING.md](../CONTRIBUTING.md).
