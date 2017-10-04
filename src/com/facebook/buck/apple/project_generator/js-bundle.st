BASE_DIR="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}"
mkdir -p "${BASE_DIR}"

cp -a "<built_bundle_path>/" "${BASE_DIR}/"
cp -a "<built_resources_path>/" "${BASE_DIR}/"
