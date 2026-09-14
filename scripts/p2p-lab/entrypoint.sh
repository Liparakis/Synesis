#!/bin/sh
set -eu

exec java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver \
  -cp '/lab/test-classes:/opt/synesis/lib/*' \
  org.synesis.link.lab.ProcessLabNode "$@"
