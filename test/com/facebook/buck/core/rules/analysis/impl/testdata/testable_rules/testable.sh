#!/bin/bash

echo "PWD: $PWD"
echo "pwd: $(pwd)"
echo "ENV: ${CUSTOM_ENV}"
echo "EXIT_CODE: $EXIT_CODE"
for arg in "$@"; do
    echo "arg[$arg]"
done
exit "$EXIT_CODE"
