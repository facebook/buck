#!/usr/bin/env bash

set -e

SRC="$1"
DEST="$2"
APPEND="$3"

cp "$SRC" "$DEST"
echo "$APPEND" >> "$DEST"
