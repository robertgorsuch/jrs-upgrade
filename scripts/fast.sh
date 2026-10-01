#!/usr/bin/env bash
# fast.sh test <TestClass[,TestClass]>   compile (Error Prone, -Werror) + those unit tests
# fast.sh accept [TestClass]             build the shaded jar and run the acceptance tests
# fast.sh fmt                            google-java-format (spotless:apply)
set -euo pipefail
cd "$(dirname "$0")/.."
COMMON=(-Djacoco.skip=true -Dspotless.check.skip=true -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false)
case "${1:-}" in
  test)   bash scripts/mvn.sh -q test "${COMMON[@]}" -Dacceptance.skip=true -Dtest="$2" ;;
  accept) bash scripts/mvn.sh -q verify "${COMMON[@]}" -DskipTests=false -Dsurefire.skip=true ${2:+-Dit.test="$2"} ;;
  fmt)    bash scripts/mvn.sh -q spotless:apply ;;
  *)      sed -n '2,4p' "$0" | sed 's/^# //'; exit 1 ;;
esac
