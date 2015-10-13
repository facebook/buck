#!/bin/sh

case $1 in
    "-version")
        echo "fakeJavac" 1>&2
        ;;
    *)
        echo "error compiling" 1>&2
        exit 42
        ;;
esac
