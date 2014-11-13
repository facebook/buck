#!/bin/sh -x

for i in "$@"; do
    case $i in
        -d)
            CLASS_OUT="$2"
            shift
            ;;
        -Abuck*)
            ABI_OUT="${1#*=}"
            shift
            ;;
        -version)
            echo "fakeJavac" 1>&2
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

echo $CLASS_OUT >> log

echo "fakeClass" > "$CLASS_OUT/Example.class"
