# Consistency pass, 2026-11-16

Taken from the repo's own screenshot harness (`ScreenshotTourTest`, run with
`./gradlew :app:connectedMinifiedTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.ScreenshotTourTest#<test>`)
on a private Pixel 6 emulator (Android 15, 1080 x 2400, 420 dpi), dark and light, from sample data. No server was contacted.
The pictures are in `after/`.

| Picture | What changed |
|---|---|
| `menu-*` | The drawer's foot is Manage servers, Settings (gear) and Sign out. "App settings" is "Settings". |
| `switcher-*` | The `Name ⌄` control opens a sheet of servers with the current one checked and Add server last. |
| `settings-alerts-off-*` | Settings is a page with Appearance, App lock and Notifications sections (the alerts section sits under them). |
