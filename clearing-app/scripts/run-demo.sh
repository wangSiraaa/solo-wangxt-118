#!/usr/bin/env bash
# 无 PostgreSQL 环境下的本地验收：用 H2 内存库 + demo 种子启动后端
set -euo pipefail
cd "$(dirname "$0")/../backend"
if [ ! -f target/internal-clearing-1.0.0.jar ]; then
  mvn -q -DskipTests package
fi
exec java -jar target/internal-clearing-1.0.0.jar --spring.profiles.active=demo
