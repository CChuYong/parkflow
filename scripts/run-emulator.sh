#!/bin/sh
set -eu
. "$(dirname -- "$0")/env.sh"
if [ "$#" -lt 1 ]; then
    echo 'Usage: ./scripts/run-emulator.sh YOUR_PLEOS_AVD_NAME [emulator-options]' >&2
    echo 'Installed AVDs:' >&2
    "$ANDROID_HOME/emulator/emulator" -list-avds
    exit 1
fi
AVD_NAME=$1
shift
exec "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" -no-snapshot "$@"
