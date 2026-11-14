#!/bin/sh
set -eu
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo 'Usage: ./scripts/install.sh /path/to/ParkFlow.apk [adb-serial]' >&2
    exit 1
fi
APK=$1
test -f "$APK" || { echo 'APK does not exist.' >&2; exit 1; }
. "$(dirname -- "$0")/env.sh"
if [ "$#" -eq 2 ]; then set -- -s "$2"; else set --; fi
adb "$@" install -r "$APK"
adb "$@" shell am start -n io.parkflow.probe/.MainActivity
