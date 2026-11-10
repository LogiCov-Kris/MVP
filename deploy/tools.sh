#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if [[ ${1:-} == build ]]; then
  cd "$root/maintenance"
  exec sbt --server -batch stage
fi
[[ -d "$root/maintenance/target/lib" ]] || { echo 'Build the operator tools first: ./deploy/tools.sh build' >&2; exit 1; }
exec java -cp "$root/maintenance/target/lib/*" logicov.tools.Tools "$@"
