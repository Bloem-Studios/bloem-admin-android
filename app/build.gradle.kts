import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional Firebase config for push; see the script for where it is read from. Without one the build is unchanged.
apply(from = rootProject.file("gradle/firebase-config.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val firebaseResValues = extra["firebaseResValues"] as Map<String, String>

// ---- Version -------------------------------------------------------------------------------------------------------
// A release is a git tag `vMAJOR.MINOR.PATCH`. The version name is the tag without its `v`; a build made after the tag
// reads `1.2.3-4-gabc1234` (4 commits past the tag), with `-dirty` added when the tree has uncommitted changes. The
// version code is MAJOR * 1,000,000 + MINOR * 1,000 + PATCH, so it rises with every release and Android accepts each one as
// an update. MINOR and PATCH must stay below 1000. BLOEM_ADMIN_VERSION_NAME and BLOEM_ADMIN_VERSION_CODE override both
// (for a source tree with no git history). With no tag at all the build is `0.0.0-dev` with code 1. See docs/releasing.md.
fun git(vararg args: String): String? = try {
    val result = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim() else null
} catch (_: Exception) {
    null
}

val describe = git("describe", "--tags", "--long", "--dirty", "--match", "v[0-9]*.[0-9]*.[0-9]*")
val describeMatch = describe?.let { Regex("""^v(\d+)\.(\d+)\.(\d+)-(\d+)-g([0-9a-f]+)(-dirty)?$""").matchEntire(it) }
fun gitVersion(): Pair<String, Int> {
    val m = describeMatch ?: return Pair("0.0.0-dev", 1)
    val major = m.groupValues[1].toInt()
    val minor = m.groupValues[2].toInt()
    val patch = m.groupValues[3].toInt()
    val ahead = m.groupValues[4]
    val sha = m.groupValues[5]
    val dirty = m.groupValues[6]
    require(minor < 1000 && patch < 1000 && major < 2000) {
        "Version tag $describe is out of range: MINOR and PATCH must be below 1000 and MAJOR below 2000"
    }
    val exact = ahead == "0" && dirty.isEmpty()
    val name = if (exact) "$major.$minor.$patch" else "$major.$minor.$patch-$ahead-g$sha$dirty"
    return Pair(name, major * 1_000_000 + minor * 1_000 + patch)
}

val resolvedVersion = gitVersion()
val appVersionName = System.getenv("BLOEM_ADMIN_VERSION_NAME")?.takeIf { it.isNotBlank() }?.removePrefix("v") ?: resolvedVersion.first
val appVersionCode = System.getenv("BLOEM_ADMIN_VERSION_CODE")?.takeIf { it.isNotBlank() }?.toInt() ?: resolvedVersion.second

// ---- Release signing -----------------------------------------------------------------------------------------------
// The key never lives in git. It comes from environment variables, or from a git-ignored `keystore.properties` in the
// repository root (environment first):
//   BLOEM_ADMIN_KEYSTORE            path to the keystore file        (storeFile)
//   BLOEM_ADMIN_KEYSTORE_PASSWORD   keystore password                (storePassword)
//   BLOEM_ADMIN_KEY_ALIAS           key alias                        (keyAlias)
//   BLOEM_ADMIN_KEY_PASSWORD        key password                     (keyPassword)
// With all four set, `assembleRelease` signs the APK. With none, it builds `app-release-unsigned.apk`, as CI does. With
// only some, the build stops, because a half-configured key is almost always a mistake.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("BLOEM_ADMIN_KEYSTORE", "storeFile")
val releaseKeystorePassword = signingValue("BLOEM_ADMIN_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("BLOEM_ADMIN_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("BLOEM_ADMIN_KEY_PASSWORD", "keyPassword")
val releaseSigningParts = listOf(releaseKeystore, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningParts.all { it != null }
check(releaseSigningConfigured || releaseSigningParts.all { it == null }) {
    "Release signing is half set up: BLOEM_ADMIN_KEYSTORE, BLOEM_ADMIN_KEYSTORE_PASSWORD, BLOEM_ADMIN_KEY_ALIAS and " +
        "BLOEM_ADMIN_KEY_PASSWORD (or storeFile, storePassword, keyAlias, keyPassword in keystore.properties) must all be " +
        "set, or none of them. See docs/releasing.md."
}

android {
    namespace = "com.bloemstudios.admin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bloemstudios.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Firebase's init provider starts FirebaseApp from these four strings. Absent, push reports "not available in this build".
        firebaseResValues.forEach { (name, value) -> resValue("string", name, value) }
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseResValues.isNotEmpty().toString())
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Signed when a key is configured above; otherwise the APK comes out unsigned.
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("minifiedTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles("proguard-minified-test-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
        }
    }
    testBuildType = "minifiedTest"
    // Sample data for the debug-only fixture screen and the screenshot tour. It is compiled into debug and into the
    // test APK only, never into a release or minifiedTest app.
    sourceSets {
        getByName("debug").java.srcDir("src/fixtures/java")
        getByName("androidTest").java.srcDir("src/fixtures/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.compose.ui:ui:1.7.4")
    implementation("androidx.compose.material3:material3:1.2.1")
    // Icons are Lucide VectorDrawables (res/drawable/lucide_*.xml); there is no Material icon library (MaterialIconsGuardTest).
    implementation("androidx.compose.material3:material3-window-size-class:1.2.1")

    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-websockets:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.biometric:biometric:1.1.0")
    // Server-down alerts and the widget refresh run from one periodic background check.
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    // The home-screen widget. Glance draws it with the app's own theme tokens.
    implementation("androidx.glance:glance-appwidget:1.2.0")
    // Push notifications through the Bloem push relay. FCM carries only a delivery id; the app fetches the text from its server.
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-client-mock:3.0.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("androidx.work:work-testing:2.11.2")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.4")
    "minifiedTestImplementation"("io.ktor:ktor-client-mock:3.0.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.4")
    // The debug-only fixture screen answers the real screens' reads from sample data.
    debugImplementation("io.ktor:ktor-client-mock:3.0.1")
    add("minifiedTestImplementation", "androidx.compose.ui:ui-test-manifest:1.7.4")
}
