# iOS screenshots in CI

- `mock_server.py`: answers the Kairo API with the sample data of "The Rivera family" (Alex, Sam and Mia),
  built by `demo_data.py` and always relative to the current time, so "today" has events, tasks and reminders.
- `demo_data.py`: the sample data itself (notes, tasks, events, reminders, lists, chat messages, weather...).
  `gen_fixtures.py` writes the same data, for a fixed date, into `KairoKit/Tests/KairoCoreTests/Fixtures`.
- `screenshots.sh`: installs the app on the iPhone and iPad simulators, signs in against the mock server
  (debug builds only, with `KAIRO_AUTOLOGIN`) and takes a screenshot of each section in light and dark mode.
  Files are named `<device>-<section>-<appearance>.png`, for example `iphone-today-light.png`,
  `iphone-tasks-light.png`, `iphone-calendar-dark.png`, `iphone-notes-light.png`, `iphone-family-dark.png`,
  `iphone-chat-light.png`, `iphone-settings-dark.png`.

The language of the UI is chosen with `KAIRO_LANG=en|es` (English by default). It is passed to the app as
`-AppleLanguages (en)` / `-AppleLanguages (es)`; Spanish files get an `-es` tag (`iphone-es-today-light.png`).
The sample data is in English in both cases.

It runs from the workflow `.github/workflows/ios-capturas.yml` (named *iOS screenshots*), which is only started by hand from
*Actions > iOS screenshots* (iPhone by default; tick "iPad" to include it, and pick the language).
The images are left in the `ios-screenshots` artifact.
Locally: `KAIRO_LANG=en bash ci/screenshots.sh <Kairo.app> screenshots` (with `KAIRO_SHOTS_IPAD=1` for the iPad).
