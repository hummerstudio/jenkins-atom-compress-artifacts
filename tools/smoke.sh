#!/usr/bin/env bash
# Unzip what the atom wrote and compare it with the workspace, byte for byte.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

ws="$(mktemp -d)"
write_input() { printf '%s' "$1" >"$ws/input.json"; }

mkdir -p "$ws/dist/nested" "$ws/logs"
printf 'jar-bytes\n' >"$ws/dist/app.jar"
printf 'deep\n' >"$ws/dist/nested/deep.txt"
printf 'spaced\n' >"$ws/dist/a file with spaces.txt"
printf 'zh\n' >"$ws/dist/报告.txt"
printf 'noise\n' >"$ws/logs/build.log"
# a file big enough to actually compress
python3 -c "
import sys
open(sys.argv[1], 'w').write('x' * 20000)
" "$ws/dist/big.txt"

# --- pack the dist tree
write_input '{"patterns": "dist/**/*", "outputFile": "out/artifacts.zip"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "pack: $(cat "$ws/output.json") $(tail -3 "$ws/atom.log")"
[ "$(output "$ws" filesAdded)" = "5" ] || fail "file count: $(cat "$ws/output.json")"
[ -f "$ws/out/artifacts.zip" ] || fail "no archive written"

reply="$(mktemp -d)"
(cd "$reply" && unzip -q "$ws/out/artifacts.zip") || fail "the archive is not a readable zip"
cmp -s "$ws/dist/app.jar" "$reply/dist/app.jar" || fail "app.jar differs after the round trip"
cmp -s "$ws/dist/nested/deep.txt" "$reply/dist/nested/deep.txt" || fail "nested paths were flattened"
cmp -s "$ws/dist/a file with spaces.txt" "$reply/dist/a file with spaces.txt" || fail "a name with spaces broke"
cmp -s "$ws/dist/报告.txt" "$reply/dist/报告.txt" || fail "a UTF-8 name broke"
cmp -s "$ws/dist/big.txt" "$reply/dist/big.txt" || fail "the large file differs"
[ ! -e "$reply/logs/build.log" ] || fail "a file outside the pattern must not be packed"

# --- 20 KB of one repeated character must compress: the ratio proves the stream really was deflated
ratio="$(output "$ws" ratio)"
python3 - "$ratio" <<'PY' || fail "the ratio says nothing was compressed: $ratio"
import sys
ratio = float(sys.argv[1])
assert ratio < 50, ratio
PY

# --- deterministic: the same input produces the same bytes, which is what makes two builds comparable
cp "$ws/out/artifacts.zip" "$ws/first.zip"
run "$ws" >"$ws/atom.log" 2>&1 || fail "second pack failed: $(cat "$ws/output.json")"
cmp -s "$ws/first.zip" "$ws/out/artifacts.zip" || fail "the same input must produce the same archive"
python3 - "$ws/out/artifacts.zip" <<'PY' || fail "entry order is not stable"
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
assert names == sorted(names), names
PY

# --- exclusion, and the archive never packing itself
write_input "{\"patterns\": \"**/*\", \"exclude\": \"**/*.log\", \"outputFile\": \"out/artifacts.zip\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "exclude run failed: $(cat "$ws/output.json")"
python3 - "$ws/out/artifacts.zip" <<'PY' || fail "exclude or self-inclusion is wrong"
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
assert not any(n.endswith(".log") for n in names), names
assert not any("artifacts.zip" in n for n in names), names
PY

# --- deleteSource empties the source tree but keeps the archive
write_input '{"patterns": "dist/**/*", "outputFile": "out/lean.zip", "deleteSource": "true"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "deleteSource run failed: $(cat "$ws/output.json")"
[ -f "$ws/out/lean.zip" ] || fail "the archive should survive"
[ -z "$(find "$ws/dist" -type f 2>/dev/null)" ] || fail "the source files should be gone"

# --- empty match is a clear error
ws2="$(mktemp -d)"; printf '{"patterns": "dist/**/*"}' >"$ws2/input.json"
run "$ws2" >"$ws2/atom.log" 2>&1 && fail "an empty workspace must be refused"
grep -q "no file matched" "$ws2/output.json" || fail "message: $(cat "$ws2/output.json")"

echo "smoke ok: round trip byte-for-byte, nested and spaced and UTF-8 names, real deflate, determinism, exclude, no self-packing, deleteSource, empty match"
