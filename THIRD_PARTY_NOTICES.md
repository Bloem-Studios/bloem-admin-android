# Third-party notices

Bloem Admin is licensed under the GNU AGPL v3 (see [LICENSE](LICENSE)). It includes or depends on the following.

## Bundled in the repository

| Material | Where | Licence |
|---|---|---|
| Plus Jakarta Sans (font) | `app/src/main/res/font/plus_jakarta_sans.ttf` | SIL Open Font License 1.1: [docs/licenses/fonts/plusjakartasans/OFL.txt](docs/licenses/fonts/plusjakartasans/OFL.txt) |
| Lucide icons, converted to Android vector drawables | `app/src/main/res/drawable/lucide_*.xml` | ISC: [docs/licenses/icons/lucide/LICENSE](docs/licenses/icons/lucide/LICENSE) |
| Gradle wrapper | `gradlew`, `gradle/wrapper/` | Apache License 2.0 |

(Android resource folders accept only resources, so the font's licence text sits in `docs/licenses/` and not beside the
font file.)

The OFL asks that the font's copyright notice and licence travel with it. This file and the licence text do that for the
app's source. An app that bundles the font should also show the notice somewhere a user can reach.

## Libraries compiled into the app

These are fetched from Maven at build time and are not stored in this repository.

| Library | Licence |
|---|---|
| AndroidX (Core, Activity, Browser, Compose UI, Material 3, Navigation, Lifecycle, Fragment, DataStore, Security Crypto, Biometric, WorkManager, Glance) | Apache License 2.0 |
| Ktor client (core, CIO, WebSockets, content negotiation, kotlinx serialization) | Apache License 2.0 |
| kotlinx.serialization and kotlinx.coroutines (the latter arrives through Ktor and AndroidX) | Apache License 2.0 |
| Kotlin standard library | Apache License 2.0 |
| Firebase Cloud Messaging (`firebase-messaging`) and the Firebase BoM | Google's Android SDK terms (proprietary binary; see below) |

Apache License 2.0 code can be combined with AGPL v3 code.

**Firebase.** Push notifications use Google's closed-source Firebase Cloud Messaging client (`firebase-messaging`, with the
Firebase BoM), which is used under Google's terms and is not covered by the AGPL. It is a dependency of every build, but it
is started only when the build has a Firebase config (the official release build does; a build from source does not, and then
the app never calls Firebase). A distribution channel that accepts only fully free software, such as F-Droid, may want a build
without it. Nothing else in the app needs it.

## Services the app contacts

- **TMDB.** The Requests screens show movie and series posters loaded from `image.tmdb.org`, from the poster paths a server
  returns. This product uses the TMDB API and images but is not endorsed or certified by TMDB. TMDB's terms ask for this
  attribution and limit commercial use, so check them before you distribute a build.
- **Firebase Cloud Messaging.** In a build with push, the app contacts Google's Firebase services to receive push messages.
- **Your servers.** Everything else the app sends goes to the Silo or Bloem servers you add.

## Used only to build and test

JUnit (Eclipse Public License 1.0), Robolectric (MIT), MockK (Apache 2.0), AndroidX Test (Apache 2.0), Ktor mock client
(Apache 2.0). None of it is shipped in the release APK.

## Names and marks

The Bloem name and tulip logo are trademarks of Bloem Studios. They appear in the app icon and in the icon source files under
`docs/design/app-icons/`. The AGPL covers the code and does not license the marks. If you distribute a modified build, give it
a different name and icon. Silo and other product names belong to their owners and are used only to say which servers the app
works with.
