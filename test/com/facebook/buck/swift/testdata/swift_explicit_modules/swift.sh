#!/bin/bash

# Hack to support testing against Xcode toolchain which does not have all the required flags yet.
filtered_args=()

while [[ $# -gt 0 ]]
do
    case $1 in
        -fmodule-file-home-is-cwd)
            # pop -Xcc -Xclang -Xcc
            filtered_args=("${filtered_args[@]::${#filtered_args[@]}-3}")
            shift
            ;;
        *)
            filtered_args+=("$1")
            shift
            ;;
    esac
done

xcrun swiftc "${filtered_args[@]}"
