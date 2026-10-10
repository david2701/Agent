# Contributing to Kairo

Thank you for your interest in Kairo. This guide explains how to build and test each part of the project, how to add
a language, and what we expect from a contribution.

For a map of the project, read the architecture section of the [README](README.md). For design decisions, see
[`design/DESIGN.md`](design/DESIGN.md). To see what is planned, read the [roadmap](docs/ROADMAP.md).

If you plan something big, open an issue first so we can talk about it before you spend time on it.

## Project layout

| Folder | What it is |
|---|---|
| `backend/` | Kotlin and Ktor server, PostgreSQL with pgvector, Flyway migrations |
| `dashboard/` | React, TypeScript and Tailwind web app, served by the backend |
| `android/` | Android phone app (`app`), Wear OS app (`wear`) and the shared API client (`core`) |
| `ios/` | iPhone, iPad and Apple Watch apps in SwiftUI, and the `KairoKit` Swift package |
| `docs/` | Documentation. The English files are the source of truth. `docs/es/` has the Spanish versions. |
| `deploy/`, `scripts/` | Caddy example, backup and restore scripts, `.env` generator |

## Build and test

### Backend

You need JDK 21 and Docker (for PostgreSQL).

```bash
docker compose up -d db
cd backend
set -a && . ../.env && set +a
KAIRO_DB_URL=jdbc:postgresql://localhost:5432/kairo ./gradlew run    # http://localhost:8080
./gradlew test                                                        # run the tests
```

To reach the database from your machine, add `ports: ["127.0.0.1:5432:5432"]` to the `db` service in a local
override. Run `./scripts/generate-env.sh` first if you do not have a `.env` yet.

### Dashboard

You need Node.js 22.

```bash
cd dashboard
npm ci
npx tsc -b            # type check
npm run build         # type check and production build
npm run dev           # http://localhost:5173, proxies /api to the backend on :8080
```

Use `KAIRO_API=http://localhost:8088 npm run dev` to point at a backend on another port.

### Android and Wear OS

You need JDK 21 and the Android SDK (platform 37).

```bash
cd android
./gradlew :app:testDebugUnitTest            # unit tests
./gradlew :app:assembleRelease :wear:assembleRelease    # phone and watch APKs
```

Without a keystore of your own, release builds are signed with the debug key. See
[`docs/ANDROID.md`](docs/ANDROID.md) for signing and for pointing the apps at your server.

### iOS, iPadOS and watchOS

The Xcode project is generated from `ios/project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen). You need
a Mac with Xcode 27 to build the apps.

```bash
brew install xcodegen
cd ios && xcodegen                  # regenerate Kairo.xcodeproj after changing project.yml
cd KairoKit && swift test           # shared logic; also runs on Linux
```

Set your own Team ID and bundle identifiers in `ios/Config/Signing.xcconfig` (see [`docs/IOS.md`](docs/IOS.md)). The
`iOS` workflow builds the app, the widgets, the extension and the watch app on every change to `ios/`. If you do not
have a Mac, open a pull request and use the CI result.

### Before you open a pull request

Run the checks for every part you changed. CI runs the backend tests, the dashboard type check and build, the Android
build with its unit tests, and the iOS workflow.

## Adding a language

Kairo supports English (the default) and Spanish. The UI follows the device or browser language, and English is the
fallback. To add a language, add it on every platform:

| Part | What to do |
|---|---|
| Dashboard | Add `dashboard/src/lib/i18n/<lang>.ts`, using the English file as the reference for keys, and register the language. |
| Android and Wear OS | Add `values-<lang>/strings.xml` next to the existing `values/strings.xml`, in the app and in the watch app. |
| iOS and watchOS | Add the language to `Localizable.xcstrings` in Xcode (String Catalog) and translate each key. |
| Backend | Add the language to the `Messages` catalog, which covers the assistant's text, notices, the briefing, weather text and dates. Add the value to the allowed values of `settings.language`. |
| Docs | Optional. Put translated docs in `docs/<lang>/`, like `docs/es/`. |

Guidelines for translations:
- Write natural text for your language, not a word-for-word translation. Keep it short and friendly.
- Keep the glossary names consistent across platforms (Today, Chat, Tasks, Calendar, Reminders, Notes, Lists, Memory,
  Recordings, Activity, Family, Settings).
- Do not change JSON field names, enum values or endpoint paths. The wire format is the same in every language.
- Wake phrases ("Hey Kairo", "Oye Kairo") are matched by the wake-word logic on each client. If your language needs a
  different phrase, include it in that logic too.

## Code style

- **Comments, log messages, test names and identifiers are in English.** Use ASCII-only names for Kotlin tests.
- User-facing text goes through the localization mechanism of each platform. Do not hard-code strings in the UI.
- Follow the style of the code around you. Keep changes focused and small, and do not reformat files you are not
  otherwise changing.
- Add or update tests when you change behavior, especially in the backend (`backend/src/test`) and in `KairoKit`.
- Keep every automated check green.
- **Never commit secrets or personal data**: no API keys, tokens, passwords, keystores, `.p8` files, `.env` files or
  real email addresses. Use example values such as `kairo.example.com`. The `.gitignore` already covers most
  secret files, but you are responsible for checking your diff.
- Commit messages are in English, in the imperative mood ("Add retry to push delivery").

## Security

Do not report security vulnerabilities in public issues. Use GitHub's private security advisories instead. The
process is described in [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your work is released under the project's license. See the `LICENSE` file in the
repository root.
