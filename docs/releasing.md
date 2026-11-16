# Releasing

This page describes how releases are built. The maintainers hold the signing key, so only they can publish an official
release. A release is a signed APK attached to a GitHub release together with its SHA-256 checksum.

## Versions

A release is a git tag `vMAJOR.MINOR.PATCH`, for example `v1.2.3`. `app/build.gradle.kts` reads it with `git describe`:

| Build | versionName | versionCode |
|---|---|---|
| exactly on tag `v1.2.3` | `1.2.3` | `1002003` |
| 4 commits after `v1.2.3` | `1.2.3-4-gabc1234` | `1002003` |
| uncommitted changes on top | `-dirty` appended | same |
| no tag at all (for example a zip of the source) | `0.0.0-dev` | `1` |

The version code is `MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`, so it rises with every release and Android accepts each one
as an update. MINOR and PATCH must be below 1000 and MAJOR below 2000, or the build stops. Only tags in exactly the `vX.Y.Z`
form count; a tag such as `v1.2.3-rc1` is ignored. A source tree with no git history can set `BLOEM_ADMIN_VERSION_NAME` and
`BLOEM_ADMIN_VERSION_CODE` instead.

## Signing

The key never lives in git. The build reads it from environment variables, or from a git-ignored `keystore.properties` in the
repository root. The environment wins.

| Environment variable | `keystore.properties` key | Meaning |
|---|---|---|
| `BLOEM_ADMIN_KEYSTORE` | `storeFile` | path to the keystore file |
| `BLOEM_ADMIN_KEYSTORE_PASSWORD` | `storePassword` | keystore password |
| `BLOEM_ADMIN_KEY_ALIAS` | `keyAlias` | key alias |
| `BLOEM_ADMIN_KEY_PASSWORD` | `keyPassword` | key password |

With all four set, `./gradlew assembleRelease` produces a signed `app-release.apk`. With none set it produces
`app-release-unsigned.apk`, which is what you get when you build from source with your own tools. With only some set, the build
stops.

Android refuses to update an app that was signed with a different key. A build you sign with your own key installs as a
separate lineage from the official one: to switch between them, uninstall first, which deletes the app's saved servers and
sign-ins.

## Push

If the build finds a Firebase config, the APK has push. Without one it works but has push off. See [push.md](push.md). The
release script prints `firebase_config=present` or `absent` before it builds.

## Building a release

You need JDK 17, the Android SDK with build-tools (`ANDROID_HOME` set), git, `sha256sum` and the signing key.

1. Make sure the commit you want to ship is checked out and the checks pass:
   `./gradlew testDebugUnitTest lintDebug assembleDebug`.
2. Tag it: `git tag -a v1.2.3 -m "Bloem Admin 1.2.3"`. Do not push the tag yet.
3. Run `scripts/release-apk.sh v1.2.3`. It refuses to run if the tag is not `vX.Y.Z`, does not exist locally or does not point
   at `HEAD`, if the working tree has uncommitted changes, or if no signing key is configured. Then it:
   - runs `./gradlew clean :app:assembleRelease`;
   - checks with `aapt2` that the APK's version name is the tag's;
   - copies the APK to `dist/bloem-admin-1.2.3.apk`;
   - verifies the signature with `apksigner verify --verbose --print-certs` and saves the output to
     `dist/bloem-admin-1.2.3.certs.txt`;
   - writes the checksum to `dist/bloem-admin-1.2.3.apk.sha256`;
   - prints the commands below. It does not run them, push, or tag.
4. Compare the signer certificate SHA-256 it printed with the published one for the app.
5. Push the tag and create a **draft** GitHub release with the APK and checksum attached:

   ```sh
   git push origin v1.2.3
   gh release create 'v1.2.3' --repo Bloem-Studios/bloem-admin-android --draft --verify-tag \
     --title 'Bloem Admin 1.2.3' --generate-notes dist/bloem-admin-1.2.3.apk dist/bloem-admin-1.2.3.apk.sha256
   ```

   The script prints the same command with full paths. Set `BLOEM_ADMIN_REPO` to change the repository it names.
6. Review the draft on GitHub, add the signer's SHA-256 to the notes, and publish it.

## Checking a download

```sh
sha256sum -c bloem-admin-1.2.3.apk.sha256
apksigner verify --print-certs bloem-admin-1.2.3.apk
```

The certificate SHA-256 should match the one in the release notes.

## Updating over an older install

An APK installs over an earlier one only when both are signed with the same key and the new version code is higher. A debug
build and a release build are signed differently, so Android reports a conflict. Uninstall the debug build first. That deletes
the app's saved servers and tokens, so add the servers again afterwards.
