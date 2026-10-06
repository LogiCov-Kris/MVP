#!/usr/bin/env bash
set -euo pipefail

name=logicov-postgres
if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
  docker start "$name"
else
  docker run -d --name "$name" \
    -e POSTGRES_DB=logicov \
    -e POSTGRES_USER=logicov \
    -e POSTGRES_PASSWORD=logicov-dev \
    -p 127.0.0.1:25432:5432 \
    -v logicov-postgres-data:/var/lib/postgresql/data \
    postgres:17
fi
