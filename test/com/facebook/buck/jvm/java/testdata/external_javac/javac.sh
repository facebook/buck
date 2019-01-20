#!/bin/bash -x

for i in "$@"; do
    case "$i" in
        @*)
            ARGS_FILE="${1#@}"
            shift
            ;;
        *)
            echo "UNEXPECTED ARGUMENT!"
            exit 1
            ;;
    esac
done

ARGS=($(<"${ARGS_FILE}"))

PREV_ARG="${ARGS[0]}"
for i in "${ARGS[@]:1}"; do
    case "$PREV_ARG" in
        -d)
            CLASS_OUT="$i"
            ;;
        -Abuck*)
            ABI_OUT="${PREV_ARG#*=}"
            ;;
        -version)
            echo "fakeJavac" 1>&2
            exit 0
            ;;
    esac
    PREV_ARG="$i"
done

echo "$CLASS_OUT" >> log

echo "fakeClass" > "$CLASS_OUT/Example.class"
