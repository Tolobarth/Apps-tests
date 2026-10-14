#!/usr/bin/env bash
# Baseline static analysis of one APK with REA.
# Usage: scripts/analyze.sh apks/<app>.apk
# Writes reports/<package>/{raw/*.json,summary.md}; copy templates/report.md
# into the same folder for the manual write-up.
set -euo pipefail

apk="$(realpath "${1:?usage: scripts/analyze.sh <path/to/app.apk>}")"
root="$(cd "$(dirname "$0")/.." && pwd)"
export REA_JADX_MCP_JAR="${REA_JADX_MCP_JAR:-$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "analyze: inspecting package (JADX load can take a minute)..."
rea inspect-android-package "$apk" --format json > "$tmp/package.json"
pkg="$(jq -r '.normalized_result.package_name' "$tmp/package.json")"
out="$root/reports/$pkg"
mkdir -p "$out/raw"
mv "$tmp/package.json" "$out/raw/package.json"

echo "analyze: checking for Flutter..."
rea identify-flutter-build "$apk" --format json > "$out/raw/flutter.json" || true

jq -r '.normalized_result.manifest.text // empty' "$out/raw/package.json" > "$out/AndroidManifest.xml"

{
  jq -r '.normalized_result as $r | .subject as $s |
    "# \($r.package_name) \($r.version_name) (\($r.version_code))\n",
    "- APK: `\($s.name)`",
    "- SHA-256: `\($s.digest.sha256)`",
    "- minSdk \($r.min_sdk) / targetSdk \($r.target_sdk)",
    "- Classes: \($r.class_count), resources: \($r.resource_count)",
    "- Analyzed with: \(.provider.name) \(.provider.version)\n",
    "## Permissions (\($r.permissions | length))\n",
    ($r.permissions[] | "- `\(.)`")' "$out/raw/package.json"
  echo
  echo "## Flutter"
  echo
  jq -r 'if .normalized_result == null then "- identification failed (see raw/flutter.json)"
         else "```json\n\(.normalized_result | tostring | .[0:1500])\n```" end' "$out/raw/flutter.json" 2>/dev/null \
    || echo "- identification failed (see raw/flutter.json)"
} > "$out/summary.md"

[ -f "$out/report.md" ] || sed "s/{{PACKAGE}}/$pkg/g" "$root/templates/report.md" > "$out/report.md"

echo "analyze: wrote $out"
