#!/bin/bash
SDK_DIR=$1
if [[ -d $SDK_DIR ]]; then
    cp "$1/annotations.jar" . && \
    cp "$1/extensions.jar" . && \
    cp "$1/idea.jar" . && \
    cp "$1/openapi.jar" . && \
    cp "$1/util.jar" .
else
    echo "please provide the path to the sdk"
    exit 1
fi

