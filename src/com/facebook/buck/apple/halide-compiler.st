#!/bin/bash

# Compilation script that translates the Xcode ARCHS environment variable into a
# list of Halide target strings, compiles the Halide shader code for each, and
# then creates a "universal" library from the resulting set of object files.

set -e

REPO_ROOT=<repo_root>
PATH_TO_COMPILER=<path_to_compiler>
PATH_TO_OUTPUT=<output_dir>
FUNC_NAME=<func_name>
OBJECTS=''

cd $REPO_ROOT
mkdir -p $PATH_TO_OUTPUT

for ARCH in $ARCHS
do
    TARGET_ARCH=""
    case "$ARCH" in
        armv7)
            TARGET_ARCH="arm-32"
            ;;
        arm64)
            TARGET_ARCH="arm-64"
            ;;
        i386)
            TARGET_ARCH="x86-32"
            ;;
        x86_64)
            TARGET_ARCH="x86-64"
            ;;
        *)
            echo "Unrecognized architecture: $ARCH"
            exit 1
    esac

    PLATFORM=$(echo "$PLATFORM_NAME" | awk -F'-' '{ if (index($0, "-") != 0) { print $2 } else { print $0 }}')
    TARGET_PLATFORM=""
    case "$PLATFORM" in
        iphonesimulator)
            TARGET_PLATFORM="osx"
            ;;
        iphoneos)
            TARGET_PLATFORM="ios"
            ;;
        *)
            echo "Unrecognized platform: $PLATFORM_NAME"
            TARGET_PLATFORM="unknown"
    esac

    TARGET="$TARGET_ARCH-$TARGET_PLATFORM"
    mkdir -p $PATH_TO_OUTPUT/$TARGET
    OBJECTS+=" $PATH_TO_OUTPUT/$TARGET/$FUNC_NAME.o"

    if [ "$TARGET_PLATFORM" != "unknown" ]
        then
            echo "Building for $TARGET."
            $PATH_TO_COMPILER -t "$TARGET" -o $PATH_TO_OUTPUT/$TARGET $FUNC_NAME
        else
            # This is hack to work around build failures on platforms we don't support (yet).
            echo "WARNING: Unknown platform \"$PLATFORM_NAME\", generating stub library."
            rm -f $PATH_TO_OUTPUT/$TARGET/$FUNC_NAME.c
            echo "void $FUNC_NAME() {}" > $PATH_TO_OUTPUT/$TARGET/$FUNC_NAME.c
            env -i clang -target $ARCH-$PLATFORM -c -o $PATH_TO_OUTPUT/$TARGET/$FUNC_NAME.o $PATH_TO_OUTPUT/$TARGET/$FUNC_NAME.c
    fi
done

# Use lipo to create a "universal" library from the object files.
lipo $OBJECTS -create -output $PATH_TO_OUTPUT/$FUNC_NAME.a
