#!/bin/sh

BIN_FILENAME="sqlite-jdbc-3.39.2.0.jar"
BIN_URL="http://central.maven.org/maven2/org/xerial/sqlite-jdbc/3.39.2.0/sqlite-jdbc-3.39.2.0.jar"
BIN_HASH="9a5c3bb5b4a99e68a3070fab72bd05ad46e350c39879fe56d046ada280ad4d48"
SOURCE_FILENAME="sqlite-jdbc-3.39.2.0-sources.jar"
SOURCE_URL="http://central.maven.org/maven2/org/xerial/sqlite-jdbc/3.39.2.0/sqlite-jdbc-3.39.2.0-sources.jar"
SOURCE_HASH="3f449331c7343da3038b954803bcfdfb5130f45c45f2d2e238ab7dfd6735e43e"

wget "$BIN_URL" -O "$BIN_FILENAME"
REAL_BIN_HASH=$(shasum -a 256 "$BIN_FILENAME" | awk {'print $1'})
if [ "$REAL_BIN_HASH" != "$BIN_HASH" ]; then
  echo "File from ${BIN_URL} has hash ${REAL_BIN_HASH}, expected ${BIN_HASH}"
  exit 1
fi

wget "$SOURCE_URL" -O "$SOURCE_FILENAME"
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
