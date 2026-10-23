#!/usr/bin/env bash
# Packs extension/ into dist/xrnrule-extension.zip, for browsers that install from a .zip
# (Quetta on Android: its extensions page → Developer mode → "(from .zip/.crx/.user.js)").
# Helium and other desktop Chromium browsers load extension/ unpacked instead.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$root/dist"
out="$root/dist/xrnrule-extension.zip"
python3 -I - "$root/extension" "$out" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for folder, dirs, files in os.walk(src):
        dirs[:] = sorted(d for d in dirs if d != "test")
        for name in sorted(files):
            path = os.path.join(folder, name)
            z.write(path, os.path.relpath(path, src))
PY
echo "$out"
