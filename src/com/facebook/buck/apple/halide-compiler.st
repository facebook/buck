set -e
cd <repo_root>

# Convert Xcode environment variables to Halide target string. Note that an
# empty target string gets us the host architecture.
TARGET=''
if [[ $CURRENT_ARCH =~ "arm" ]]
then
    TARGET='arm-64-ios'
fi

mkdir -p <output_dir>
<path_to_compiler> -t "$TARGET" -o <output_dir> <output_prefix>
