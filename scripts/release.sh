#!/usr/bin/env bash
# Build a signed release, run the core tests, install on the connected phone and precompile it.
#   scripts/release.sh 7               → versionCode yyMMdd07, versionName 1.1.0-nightly.yyyyMMdd.7
#   scripts/release.sh 7 --no-install
# Signing keys come from ~/.config/morph-signing/keystore.properties (outside the repo); they're never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
n="${1:?build number for today, e.g. 3}"
props="$HOME/.config/morph-signing/keystore.properties"
eval "$(python3 - "$props" <<'PY'
import shlex, sys
keys = {"storeFile": "MORPH_KEYSTORE_FILE", "storePassword": "MORPH_KEYSTORE_PASSWORD", "keyAlias": "MORPH_KEY_ALIAS"}
for line in open(sys.argv[1]):
    k, _, v = line.strip().partition("=")
    if k.strip() in keys: print(f"export {keys[k.strip()]}={shlex.quote(v.strip())}")
PY
)"
code="$(date +%y%m%d)$(printf %02d "$n")"
name="1.1.0-nightly.$(date +%Y%m%d).$n"
./gradlew -q :core:test :app:assembleRelease -PversionCode="$code" -PversionName="$name"
echo "built $name ($code)"
if [[ "${2:-}" != "--no-install" ]]; then
  adb install -r app/build/outputs/apk/release/app-release.apk >/dev/null
  adb shell cmd package compile -m speed -f bhupendra.morph >/dev/null
  echo "installed"
fi
