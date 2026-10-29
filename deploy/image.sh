#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
image=${1:-logicov:local}
./deploy/package.sh
docker build --file deploy/Dockerfile --tag "$image" target/runtime
