#!/bin/sh
set -eu

export PYTHONPATH="`dirname "$0"`"

python3 -m artificialproject "$@"
