# Bloem Admin widgets

Every widget, drawn from made-up sample content (`WidgetSamples`), and the two Quick Settings tiles and the progress
notification on the real system UI. No server was contacted and nothing here comes from a production server; the names
(ana, ben, Harbour Lights, edge-b) are invented. Rules: [docs/design.md](../../design.md).

| Files | What |
|---|---|
| `server-status-{small,medium,large}-{dark,light}.png` | Server status: a summary, then a row per server |
| `now-playing-…`, `requests-…` | Now playing and Requests at the three sizes |
| `libraries-{medium,large}-…`, `nodes-{medium,large}-…` | Libraries and scans, Nodes and resources (no small layout) |
| `*-counts-only-*-….png` | The same widgets as a lock-screen widget draws them with **Show details on lock screen** off |
| `*-state-{loading,signed-out,unreachable,unsupported,empty,stale}-dark.png` | Every state at medium (`unsupported` says the server lacks the feature; `unreachable` and `stale` carry "Updated" and a time) |
| `requests-state-action-failed-dark.png`, `server-status-state-*` | A failed Approve, and the Server status states |
| `tiles-quick-settings-{dark,light}.png` | The Requests and Now playing tiles |
| `progress-notification-{dark,light}.png` | An ongoing backup (63%) and library scan |

The widgets are rendered at 158 x 158 dp (small), 320 x 112 dp (medium) and 320 x 276 dp (large) over a stand-in wallpaper, on an
API 35 emulator (1080 x 2400). Glance composes the real content to RemoteViews, which are inflated and drawn the way a launcher
gets them. Re-take them with:

```sh
./gradlew connectedMinifiedTestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.WidgetScreenshotTest,com.bloemstudios.admin.ui.WidgetSurfacesScreenshotTest
# PNGs: app/build/outputs/connected_android_test_additional_output/
```
