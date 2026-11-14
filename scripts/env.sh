#!/bin/sh
# Sourced only by scripts in this directory. Respect the caller's tool locations.
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
        ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        ANDROID_HOME="$HOME/Android/Sdk"
    else
        echo 'Set ANDROID_HOME to your Android SDK directory.' >&2
        exit 1
    fi
fi
if [ ! -d "$ANDROID_HOME" ]; then
    echo 'ANDROID_HOME is not an existing directory.' >&2
    exit 1
fi
export ANDROID_HOME
ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_SDK_ROOT
PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export PATH
