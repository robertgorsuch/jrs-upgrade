#!/usr/bin/env bash
# Runs Maven with the JDK 21 this project requires. Usage: scripts/mvn.sh verify
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JRSUPGRADE_JDK:=}"
if [ -z "$JRSUPGRADE_JDK" ]; then
  for c in /usr/lib/jvm/temurin-21-jdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 "$HOME/tools/jdk-21" "/c/Program Files/Microsoft/jdk-21.0.9.10-hotspot"; do
    [ -x "$c/bin/java" ] && JRSUPGRADE_JDK="$c" && break
  done
fi
[ -x "$JRSUPGRADE_JDK/bin/java" ] || { echo "JDK 21 not found; set JRSUPGRADE_JDK" >&2; exit 1; }
export JAVA_HOME="$JRSUPGRADE_JDK"; export PATH="$JAVA_HOME/bin:$PATH"
exec ./mvnw -B "$@"
