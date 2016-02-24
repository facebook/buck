#!/bin/bash
SDK_DIR=$1
if [[ -d $SDK_DIR ]]; then
    cp "$1/annotations.jar" . && \
    cp "$1/extensions.jar" . && \
    cp "$1/idea.jar" . && \
    cp "$1/openapi.jar" . && \
    cp "$1/util.jar" .
else
    echo "Please provide the path to the sdk"
    echo "Example usage: 'sh get_jars.sh /Applications/IntelliJ\ IDEA\ 15\ CE.app/Contents/lib/'"
    exit 1
fi

