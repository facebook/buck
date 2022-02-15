#!/bin/sh

BIN_FILENAME="sqlite-jdbc-3.36.0.3.jar"
BIN_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.36.0.3/sqlite-jdbc-3.36.0.3.jar"
BIN_HASH="af3a3376391e186a0fed63ecd414b72a882bf452667b490a0be3abf85b637d3f"
SOURCE_FILENAME="sqlite-jdbc-3.36.0.3-sources.jar"
SOURCE_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.36.0.3/sqlite-jdbc-3.36.0.3-sources.jar"
SOURCE_HASH="abeee9e28cdadbc5e4749f49745205a2295402bbcd43fe1ee9678db016dfcbf7"

curl "$BIN_URL" -o "$BIN_FILENAME"
REAL_BIN_HASH=$(shasum -a 256 "$BIN_FILENAME" | awk {'print $1'})
if [ "$REAL_BIN_HASH" != "$BIN_HASH" ]; then
  echo "File from ${BIN_URL} has hash ${REAL_BIN_HASH}, expected ${BIN_HASH}"
  exit 1
fi

curl "$SOURCE_URL" -o "$SOURCE_FILENAME"
REAL_SOURCE_HASH=$(shasum -a 256 "$SOURCE_FILENAME" | awk {'print $1'})
if [ "$REAL_SOURCE_HASH" != "$SOURCE_HASH" ]; then
  echo "File from ${SOURCE_URL} has hash ${REAL_SOURCE_HASH}, expected ${SOURCE_HASH}"
  exit 1
fi

zip -d "$BIN_FILENAME" "org/sqlite/native/FreeBSD/x86_64/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/android-arm/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/arm/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/armv6/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/armv7/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/ppc64/libsqlitejdbc.so"
