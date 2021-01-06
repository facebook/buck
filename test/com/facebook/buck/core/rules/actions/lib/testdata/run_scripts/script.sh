#!/bin/bash

echo "Message on stdout" >&1
echo "Message on stderr" >&2
for arg in "$@"; do
  echo "arg[${arg}]" >&2
done
echo "PWD: $PWD" >&2
echo "CUSTOM_ENV: $CUSTOM_ENV" >&2

if [ -n "$EXIT_CODE" ]; then
  exit "$EXIT_CODE"
fi
exit 0
