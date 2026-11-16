# Admin widgets

Made-up sample content only (`WidgetSamples`: maria, Dune: Part Two, Bloem Home, the Music, TV shows and Movies libraries). The widget
renders are the real Glance content, composed to RemoteViews at the exact size and drawn the way a launcher gets them, on an API 35
emulator. No phone and no server was used. Rules: [docs/design.md](../../../design.md).

| Folder | What |
|---|---|
| `after/` | The five widgets at five shapes, dark and light. Names: `<widget>-<small|medium|large|wide-short|tall-narrow>-<dark|light>.png`. Also `now-playing-xl-*` (a tablet), `*-large-text-*` (font scale 1.3), `*-counts-only-*` (the lock screen), `*-1-stream`, `*-2-streams`, and `now-playing-state-*` (loading, signed out, empty, stale, unsupported, can't reach) |
| `refresh-evidence/` | `dumpsys jobscheduler` before a widget is placed, after, and after it is removed |

Sizes: small 158 x 158 dp (2 x 2), medium 320 x 112 (4 x 2), large 320 x 276 (4 x 4), wide and short 320 x 62 (4 x 1), tall and narrow 158 x 276 (2 x 4).

Re-take: `ANDROID_SERIAL=<your own emulator> ./gradlew connectedMinifiedTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.WidgetResizeScreenshotTest`
(PNGs in `app/build/outputs/connected_android_test_additional_output/`).
