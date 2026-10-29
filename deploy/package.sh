#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sbt --server -batch 'server/Universal/stage'
