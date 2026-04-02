#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "==> Maven runtime"
mvn -version

echo "==> Clean"
mvn -B -ntp clean

echo "==> Compile"
mvn -B -ntp -DskipTests compile

echo "==> Test"
mvn -B -ntp test
