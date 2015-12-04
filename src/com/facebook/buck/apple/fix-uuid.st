cd <root_path>

BUCK_BUNDLE_OUTPUT_PATH=`<path_to_buck> targets --show-output <buck_target><buck_flavor> | awk '{print $2}'`
BUCK_DSYM_OUTPUT_PATH="$BUCK_BUNDLE_OUTPUT_PATH.dSYM"

echo "Sanitized bundle path: $BUCK_BUNDLE_OUTPUT_PATH"
echo "Sanitized dSYM path: $BUCK_DSYM_OUTPUT_PATH"

rm -r <resolved_bundle_destination> 2> /dev/null || true
rm -r <resolved_dsym_destination> 2> /dev/null || true

mkdir -p <resolved_bundle_destination_parent>

cp -r $BUCK_BUNDLE_OUTPUT_PATH <resolved_bundle_destination>
cp -r $BUCK_DSYM_OUTPUT_PATH <resolved_dsym_destination>

export LANG=C
export LC_ALL=C
sed -i '' 's|<comp_dir>|<source_dir>|g' <resolved_dsym_destination>/Contents/Resources/DWARF/<binary_name>

python <path_to_fix_uuid_script> --verbose <resolved_bundle_destination> <resolved_dsym_destination> <binary_name>