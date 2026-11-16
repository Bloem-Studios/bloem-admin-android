# AndroidX Test references compile-time-only Error Prone annotations. They
# are not part of the instrumentation runtime and need no retained classes.
-dontwarn com.google.errorprone.annotations.**

# The target application remains fully release-minified. Keep the test
# harness itself intact because AndroidJUnitRunner and JUnit discover most of
# this surface reflectively.
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class org.junit.** { *; }
-keep class com.bloemstudios.admin.MinifiedRuntimeSmokeTest { *; }
