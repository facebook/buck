#!/bin/sh

BIN_FILENAME="sqlite-jdbc-3.20.0.jar"
BIN_URL="http://central.maven.org/maven2/org/xerial/sqlite-jdbc/3.20.0/sqlite-jdbc-3.20.0.jar"
BIN_HASH="143b1c0a453c9a8f77be14209ea15391d1e0eb93348fcfabf03cc227b0edae73"
SOURCE_FILENAME="sqlite-jdbc-3.20.0-sources.jar"
SOURCE_URL="http://central.maven.org/maven2/org/xerial/sqlite-jdbc/3.20.0/sqlite-jdbc-3.20.0-sources.jar"
SOURCE_HASH="db6a12de3990dac9eb5af4f97d01e160f0c875bdad88a5e28aee39467d915f4b"

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
