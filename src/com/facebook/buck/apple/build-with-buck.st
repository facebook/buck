set -e
cd <repo_root>
<path_to_buck> build <build_flags> <escaped_build_target>
rm -r <resolved_bundle_destination> 2> /dev/null || true
mkdir -p <resolved_bundle_destination_parent>
cp -r <resolved_bundle_source> <resolved_bundle_destination>
cp -r <resolved_dsym_source> <resolved_dsym_destination>
export LANG=C
export LC_ALL=C
sed -i '' 's|<comp_dir>|<source_dir>|g' <resolved_dsym_destination>/Contents/Resources/DWARF/<binary_name>
