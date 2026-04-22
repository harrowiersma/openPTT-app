#!/usr/bin/env bash
# Clean-build the foss-debug APK and ship it to prod's /var/openptt/apk/.
# Future CI calls this exact script.
#
# Requires:
#   - JAVA_HOME=openjdk21 (script sets it for you on macOS+Homebrew layout)
#   - ~/.ssh/id_ed25519_ptt with access to root@ptt.harro.ch
#   - openPTT-app/app/debug.keystore committed (committed in
#     2026-04-22 deterministic-debug-signing).
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK="${REPO_ROOT}/app/build/outputs/apk/foss/debug/openptt-foss-debug.apk"
PROD_HOST="root@ptt.harro.ch"
PROD_PATH="/var/openptt/apk/openptt-foss-debug.apk"
SSH_KEY="${HOME}/.ssh/id_ed25519_ptt"
PROD_URL="https://ptt.harro.ch/apk/openptt-foss-debug.apk"

if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi

step() { printf '\n\033[1;36m==>\033[0m %s\n' "$1"; }
ok()   { printf '    \033[32mOK\033[0m %s\n' "$1"; }
fail() { printf '\n\033[1;31m!! \033[0m %s\n' "$1" >&2; exit 1; }

step "Building APK"
( cd "$REPO_ROOT" && ./gradlew clean :app:assembleFossDebug ) \
    || fail "gradle build failed"
LOCAL_HASH=$(md5 -q "$APK")
ok "local md5: $LOCAL_HASH ($(wc -c <"$APK") bytes)"

step "Uploading to $PROD_HOST:$PROD_PATH"
scp -i "$SSH_KEY" -q "$APK" "$PROD_HOST:$PROD_PATH" \
    || fail "scp failed"

step "Verifying remote hash"
REMOTE_HASH=$(ssh -i "$SSH_KEY" "$PROD_HOST" \
    "md5sum '$PROD_PATH' | awk '{print \$1}'")
ok "prod md5:  $REMOTE_HASH"

if [[ "$LOCAL_HASH" != "$REMOTE_HASH" ]]; then
    fail "hash mismatch — upload corrupted? local=$LOCAL_HASH remote=$REMOTE_HASH"
fi

step "Done"
ok "APK live at $PROD_URL"
