#!/usr/bin/env bash
# Builds the signed release APK for a version tag, verifies it, writes its SHA-256 checksum, and prints the command that
# creates a DRAFT GitHub release with both files attached. It never runs that command, never pushes, and never tags.
#
#   scripts/release-apk.sh v1.2.3
#
# Needs, outside git: the signing key (BLOEM_ADMIN_KEYSTORE, BLOEM_ADMIN_KEYSTORE_PASSWORD, BLOEM_ADMIN_KEY_ALIAS and
# BLOEM_ADMIN_KEY_PASSWORD in the environment, or storeFile / storePassword / keyAlias / keyPassword in a git-ignored
# keystore.properties). Optionally the Firebase config for push (see docs/push.md); without it the APK has push off.
# Also: JDK 17, the Android SDK (ANDROID_HOME) with build-tools, git and sha256sum. See docs/releasing.md.
set -euo pipefail

REPO_SLUG="${BLOEM_ADMIN_REPO:-Bloem-Studios/bloem-admin-android}"

die() { echo "release-apk: $*" >&2; exit 1; }

[ $# -eq 1 ] || die "usage: scripts/release-apk.sh vMAJOR.MINOR.PATCH"
TAG="$1"
[[ "$TAG" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "'$TAG' is not a version tag like v1.2.3"
VERSION="${TAG#v}"

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"

# The tag must exist and point at the commit being built, and the tree must be clean, so the APK is exactly that tag.
git rev-parse -q --verify "refs/tags/$TAG^{commit}" >/dev/null || die "tag $TAG does not exist locally. Create it first: git tag -a $TAG -m 'Bloem Admin $VERSION'"
[ "$(git rev-parse "$TAG^{commit}")" = "$(git rev-parse HEAD)" ] || die "tag $TAG does not point at the checked-out commit"
[ -z "$(git status --porcelain)" ] || die "the working tree has uncommitted changes"

# A release APK must be signed.
have_env() { [ -n "${!1:-}" ]; }
if ! { have_env BLOEM_ADMIN_KEYSTORE && have_env BLOEM_ADMIN_KEYSTORE_PASSWORD && have_env BLOEM_ADMIN_KEY_ALIAS && have_env BLOEM_ADMIN_KEY_PASSWORD; }; then
  [ -f keystore.properties ] || die "no signing key: set the four BLOEM_ADMIN_KEY* variables or create keystore.properties (docs/releasing.md)"
fi

# Tools.
[ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME is not set"
BUILD_TOOLS="$(ls -1 "$ANDROID_HOME/build-tools" 2>/dev/null | sort -V | tail -1)"
[ -n "$BUILD_TOOLS" ] || die "no build-tools under $ANDROID_HOME/build-tools"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner"
AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2"
[ -x "$APKSIGNER" ] || die "apksigner not found at $APKSIGNER"
command -v sha256sum >/dev/null || die "sha256sum not found"

echo "==> Push configuration"
FIREBASE_LINE="$(./gradlew -q :app:printFirebaseConfig | grep '^firebase_config=' || true)"
echo "    ${FIREBASE_LINE:-firebase_config=unknown} (absent means this APK has push turned off)"

echo "==> Building $TAG"
./gradlew --console=plain clean :app:assembleRelease

APK_BUILT="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK_BUILT" ] || die "no signed APK at $APK_BUILT (an unsigned build means no signing key was picked up)"

echo "==> Checking the version baked into the APK"
if [ -x "$AAPT2" ]; then
  BADGING="$("$AAPT2" dump badging "$APK_BUILT" | sed -n 1p)"
  case "$BADGING" in
    *"versionName='$VERSION'"*) ;;
    *) die "the APK's version is not $VERSION: $BADGING" ;;
  esac
  echo "    $BADGING"
fi

DIST="$ROOT/dist"
mkdir -p "$DIST"
APK="$DIST/bloem-admin-$VERSION.apk"
SUM="$APK.sha256"
cp "$APK_BUILT" "$APK"

echo "==> Verifying the signature"
"$APKSIGNER" verify --verbose --print-certs "$APK" | tee "$DIST/bloem-admin-$VERSION.certs.txt"

echo "==> Writing the checksum"
( cd "$DIST" && sha256sum "$(basename "$APK")" > "$(basename "$SUM")" )
cat "$SUM"

cat <<EOF

Built and verified:
  $APK
  $SUM

Check the signer's certificate SHA-256 above against the one you published for the app. Push the tag, then create the DRAFT
release (this script does not run it):

  git push origin $TAG
  gh release create '$TAG' --repo '$REPO_SLUG' --draft --verify-tag --title 'Bloem Admin $VERSION' --generate-notes '$APK' '$SUM'

Review the draft on GitHub, add the signer's SHA-256 to the notes, then publish it.
EOF
