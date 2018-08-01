#!/usr/bin/env bash

OUT="$1"
mkdir -p "$OUT" "$OUT/b" "$OUT/c"

echo 'var externalA;' > "$OUT/a.js"
echo 'var externalB;' > "$OUT/b/index.js"
echo '{"main": "c.js"};' > "$OUT/c/package.json"
echo 'var externalC;' > "$OUT/c/c.js"
