#!/bin/sh
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

find_java_home() {
    if [ -n "$JAVA_HOME" ]; then
        echo "$JAVA_HOME"
    else
        echo "$(dirname $(dirname $(readlink -f $(which java))))"
    fi
}

JAVA_HOME="$(find_java_home)"
JAVACMD="$JAVA_HOME/bin/java"

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
