#!/usr/bin/env bash
# Installs the REA CLI and the pinned headless JADX engine REA uses for APKs.
# Idempotent: safe to run on every session start.
set -euo pipefail

REA_VERSION="${REA_VERSION:-6.5.0}"
JADX_JAR_URL="https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar"
JADX_JAR_SHA256="6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"
JADX_DIR="$HOME/.local/share/rea/android"
JADX_JAR="$JADX_DIR/jadx-headless-mcp-0.7.1-all.jar"

for tool in node npm java javac ps jq curl; do
  command -v "$tool" >/dev/null || { echo "setup: missing required tool: $tool" >&2; exit 1; }
done

if [ "$(rea --version 2>/dev/null || true)" != "$REA_VERSION" ]; then
  npm install --global --no-fund --no-audit "rea-agents@$REA_VERSION" >/dev/null
fi

mkdir -p "$JADX_DIR"
if ! echo "$JADX_JAR_SHA256  $JADX_JAR" | sha256sum -c --status - 2>/dev/null; then
  curl -fsSL -o "$JADX_JAR" "$JADX_JAR_URL"
  echo "$JADX_JAR_SHA256  $JADX_JAR" | sha256sum -c --quiet -
fi

# Expose the JAR path to the rest of the Claude Code session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export REA_JADX_MCP_JAR=\"$JADX_JAR\"" >> "$CLAUDE_ENV_FILE"
fi

echo "setup: rea $(rea --version), JADX jar at $JADX_JAR"
