# Bloem look

Screens from `ScreenshotTourTest`, which draws the real screens from built-in sample data on an emulator (1080 x 2400, 420 dpi),
in the dark and light schemes, saved here at half size, in `after/`. No server was contacted and nothing here comes from a production server.

The well-known titles shown (Dune: Part Two, Severance, Planet Earth III, The River) are sample data from the built-in fixtures.

Re-take them with:

```sh
./gradlew connectedMinifiedTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.ScreenshotTourTest
# PNGs: app/build/outputs/connected_android_test_additional_output/
```
