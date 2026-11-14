#!/bin/sh
set -eu
case "${1:-release}" in
    demo) VARIANT=Demo; OUTPUT=demo ;;
    release) VARIANT=Release; OUTPUT=release ;;
    *) echo 'Usage: ./scripts/build-signed.sh [demo|release]' >&2; exit 1 ;;
esac
if [ -z "${PARKFLOW_SIGNING_PROPERTIES:-}" ] || [ ! -r "$PARKFLOW_SIGNING_PROPERTIES" ]; then
    echo 'Set PARKFLOW_SIGNING_PROPERTIES to a readable signing properties file outside this repository.' >&2
    exit 1
fi
. "$(dirname -- "$0")/env.sh"
cd "$APP_ROOT"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
for tool in apksigner aapt zipalign; do
    if [ ! -x "$BUILD_TOOLS/$tool" ]; then
        echo "Install Android build-tools 34.0.0 (missing $tool)." >&2
        exit 1
    fi
done
./gradlew --no-daemon "assemble$VARIANT" "lint$VARIANT" "test${VARIANT}UnitTest"
APK="$APP_ROOT/app/build/outputs/apk/$OUTPUT/app-$OUTPUT.apk"
test -f "$APK"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK"
"$BUILD_TOOLS/zipalign" -c 4 "$APK"
if "$BUILD_TOOLS/aapt" dump badging "$APK" | grep -q '^application-debuggable'; then
    echo 'Signed deliverable must not be debuggable.' >&2
    exit 1
fi
printf 'Verified signed, non-debuggable %s APK: %s\n' "$OUTPUT" "$APK"
