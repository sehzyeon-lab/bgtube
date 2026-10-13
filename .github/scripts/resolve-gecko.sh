#!/usr/bin/env bash
# Mozilla Maven 에서 GeckoView 아티팩트/최신 릴리스 버전을 찾아 GITHUB_OUTPUT 형식(key=value)으로 출력한다.
# 사용법: resolve-gecko.sh <abi: arm64-v8a|x86_64|...>
set -euo pipefail
ABI="${1:?abi required}"
BASE="https://maven.mozilla.org/maven2/org/mozilla/geckoview"

for artifact in "geckoview-$ABI" "geckoview-omni" "geckoview"; do
  meta="$(curl -fsSL "$BASE/$artifact/maven-metadata.xml" 2>/dev/null || true)"
  [ -z "$meta" ] && { echo "skip $artifact (no metadata)" >&2; continue; }
  version="${GECKO_VERSION:-}"
  [ -z "$version" ] && version="$(sed -n 's:.*<release>\(.*\)</release>.*:\1:p' <<<"$meta" | head -1)"
  [ -z "$version" ] && version="$(sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p' <<<"$meta" | head -1)"
  [ -z "$version" ] && version="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' <<<"$meta" | tail -1)"
  echo "using $artifact:$version" >&2
  echo "artifact=$artifact"
  echo "version=$version"
  exit 0
done
echo "GeckoView artifact not found under $BASE" >&2
curl -fsSL "$BASE/" >&2 || true
exit 1
