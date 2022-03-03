#!/bin/sh

VERSION="3.34.0"
BIN_FILENAME="sqlite-jdbc-$VERSION.jar"
BIN_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/$VERSION/sqlite-jdbc-$VERSION.jar"
BIN_HASH="fd29bb0124e3f79c80b2753162a6a3873c240bcf"
SOURCE_FILENAME="sqlite-jdbc-$VERSION-sources.jar"
SOURCE_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/$VERSION/sqlite-jdbc-$VERSION-sources.jar"
SOURCE_HASH="9d5be0d6ec51a77bec54f4e513ac739e462c830a"

wget "$BIN_URL" -O "$BIN_FILENAME"
REAL_BIN_HASH=$(shasum -a 1 "$BIN_FILENAME" | awk {'print $1'})
if [ "$REAL_BIN_HASH" != "$BIN_HASH" ]; then
  echo "File from ${BIN_URL} has hash ${REAL_BIN_HASH}, expected ${BIN_HASH}"
  exit 1
fi

wget "$SOURCE_URL" -O "$SOURCE_FILENAME"
REAL_SOURCE_HASH=$(shasum -a 1 "$SOURCE_FILENAME" | awk {'print $1'})
if [ "$REAL_SOURCE_HASH" != "$SOURCE_HASH" ]; then
  echo "File from ${SOURCE_URL} has hash ${REAL_SOURCE_HASH}, expected ${SOURCE_HASH}"
  exit 1
fi

zip -d "$BIN_FILENAME" "org/sqlite/native/FreeBSD/x86_64/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/android-arm/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/arm/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/armv7/libsqlitejdbc.so"
zip -d "$BIN_FILENAME" "org/sqlite/native/Linux/ppc64/libsqlitejdbc.so"
