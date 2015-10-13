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
            if [[ $i == *.zip ]]; then
                exit 1
            fi
            shift
            ;;
    esac
done

echo $CLASS_OUT >> log
echo $ABI_OUT >> log

echo "fakeClass" > "$CLASS_OUT/Example.class"
echo "0000000000000000000000000000000000000000" > $ABI_OUT
