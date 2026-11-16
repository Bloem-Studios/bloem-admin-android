# The widgets' 15-minute job, on a real system image

`dumpsys jobscheduler`, taken by `WidgetJobInstalledTest` on an Android 15 emulator after a fresh install:

- `jobscheduler-before-widget.txt`: no WorkManager job for the app.
- `jobscheduler-after-widget-placed.txt`: a widget bound by the system **with no configuration screen and no note kept by the app**
  (the case that left the phone without a job). Job `/0` is the widgets' job: `NET` (needs a network) and `TIME=+9m59s` (a 15-minute period
  with a 5-minute flex window, so its first run is 10 to 15 minutes out). `/2` and `/3` are Glance's own widget-session jobs.
- `jobscheduler-after-widget-removed.txt`: the last widget removed, and job `/0` is gone.

Re-run with `ANDROID_SERIAL=<your own emulator> ./gradlew connectedMinifiedTestAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bloemstudios.admin.ui.WidgetJobInstalledTest`.
It failed about two runs in three before the flex window was added to the job (see `WidgetWork`), and passed six runs in six after.
