#!/bin/sh
set -eu
. "$(dirname -- "$0")/env.sh"
cd "$APP_ROOT"
exec ./gradlew --no-daemon assembleDebug lintDebug testDebugUnitTest "$@"
