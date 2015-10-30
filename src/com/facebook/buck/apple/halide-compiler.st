#!/bin/bash

# Compilation script that translates the Xcode ARCHS environment variable into a
# list of Halide target strings, compiles the Halide shader code for each, and
# then creates a "universal" library from the resulting set of object files.

set -e

REPO_ROOT=<repo_root>
PATH_TO_COMPILER=<path_to_compiler>
PATH_TO_OUTPUT=<output_dir>
OUTPUT_PREFIX=<output_prefix>

cd $REPO_ROOT
mkdir -p $PATH_TO_OUTPUT

for ARCH in $ARCHS
do
    TARGET=""
    case "$ARCH" in
        armv7)
            echo "Building for armv7"
            TARGET="arm-32-ios"
            ;;
        arm64)
            echo "Building for arm64"
            TARGET="arm-64-ios"
            ;;
        i386)
            # I'm not sure if osx is correct here ... should it be iOS if we're
            # going to be running in the simulator?
            echo "Building for i386"
            TARGET="x86-32-osx"
            ;;
        x86_64)
            echo "Building for x86_64"
            TARGET="x86-64-osx"
            ;;
        *)
            echo "Unrecognized architecture: $ARCH"
            exit 1
    esac
    $PATH_TO_COMPILER -t "$TARGET" -o $PATH_TO_OUTPUT $OUTPUT_PREFIX
done

# TODO: lipo the object files into a "universal" library here
lipo $PATH_TO_OUTPUT/*.o -create -output $PATH_TO_OUTPUT/$OUTPUT_PREFIX.a

# Now generate the header.
$PATH_TO_COMPILER -o $PATH_TO_OUTPUT --header-only $OUTPUT_PREFIX
