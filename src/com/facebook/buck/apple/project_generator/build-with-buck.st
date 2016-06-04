set -e
cd <repo_root>

BUILD_WITH_BUCK_COMMAND=`python <path_to_build_with_buck_py> <repo_root> <path_to_buck> -- "<build_flags>" <escaped_build_target> <buck_dwarf_flavor> <buck_dsym_flavor>`
echo $BUILD_WITH_BUCK_COMMAND

BUCK_BUNDLE_OUTPUT_PATH=`eval $BUILD_WITH_BUCK_COMMAND | awk 'NR>1 {print $2}'`
echo "Sanitized bundle path: $BUCK_BUNDLE_OUTPUT_PATH"

rm -r <resolved_bundle_destination> 2> /dev/null || true
rm -r <resolved_dsym_destination> 2> /dev/null || true
mkdir -p <resolved_bundle_destination_parent>
cp -r $BUCK_BUNDLE_OUTPUT_PATH <resolved_bundle_destination>

if [ "$DEBUG_INFORMATION_FORMAT" = "dwarf-with-dsym" ] || [ "<force_dsym>" == "true" ]; then
    echo "DSYM MODE"
    BUCK_DSYM_OUTPUT_PATH="$BUCK_BUNDLE_OUTPUT_PATH.dSYM"
    echo "Sanitized dSYM path: $BUCK_DSYM_OUTPUT_PATH"
    cp -r $BUCK_DSYM_OUTPUT_PATH <resolved_dsym_destination>

    export LANG=C
    export LC_ALL=C
    sed -i '' 's|<comp_dir>|<padded_source_dir>|g' <resolved_dsym_destination>/Contents/Resources/DWARF/<binary_name>

    python <path_to_fix_uuid_script> --verbose <resolved_bundle_destination> <resolved_dsym_destination> <binary_name>
else
    echo "DWARF MODE"
    echo "Absolutifying object file paths in binary: $BUCK_BUNDLE_OUTPUT_PATH/<binary_name>"
    <path_to_buck> machoutils absolutify_object_paths --binary $BUCK_BUNDLE_OUTPUT_PATH/<binary_name> --output <resolved_bundle_destination>/<binary_name> --old_compdir "<comp_dir>" --new_compdir "<new_comp_dir>"
fi