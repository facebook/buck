#!/bin/sh

for i in gcc g++ clang clang++; do
    if [ -x $(which i) ]; then
        exec $(which $i) "$@"
    fi
done

echo "No linker found"
exit 1
