BASE_DIR=${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}
JS_OUT=${BASE_DIR}/<bundle_name>
SOURCE_MAP=${TEMP_DIR}/rn_source_map/<bundle_name>.map

mkdir -p `dirname ${JS_OUT}`
mkdir -p `dirname ${SOURCE_MAP}`

cp <built_bundle_path> ${JS_OUT}
cp -a <built_resources_path>/ ${BASE_DIR}/
cp <built_source_map_path> ${SOURCE_MAP}
