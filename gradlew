#!/usr/bin/env sh
set -eu
VERSION=8.9
DIST="$HOME/.gradle/manual-dists/gradle-$VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$HOME/.gradle/manual-dists"
  ZIP="${TMPDIR:-/tmp}/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -O "$ZIP"
  else
    echo "Install curl or wget to download Gradle." >&2
    exit 1
  fi
  unzip -oq "$ZIP" -d "$HOME/.gradle/manual-dists"
fi
exec "$DIST/bin/gradle" "$@"
