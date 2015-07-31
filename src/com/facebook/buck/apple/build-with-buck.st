buck build <build_flags> <escaped_build_target>
rm -r <resolved_bundle_destination> 2> /dev/null || true
mkdir -p <resolved_bundle_destination_parent>
cp -r <resolved_bundle_source> <resolved_bundle_destination>
export LANG=C
export LC_ALL=C
sed -i '' 's|<comp_dir>|<source_dir>|g' <resolved_bundle_destination>/<binary_name>.dSYM/Contents/Resources/DWARF/<binary_name>
