#!/usr/bin/env bash -e

# ====================================================================
#  Copies IntelliJ jars from a local IntelliJ installation into
#  buck's third-party/java/intellij directory.
#
#  Note that some libraries are copied in their entirety, and others
#  are stubbed/ABI jars, since their purpose is merely to enable
#  compilation.
# ====================================================================

# Some files are copied, some are stubbed
LIBS_TO_COPY=(
  annotations.jar
  extensions.jar
  openapi.jar
  util.jar
)
LIBS_TO_STUB=(
  idea.jar
)
ALL_LIBS=("${LIBS_TO_COPY[@]}" "${LIBS_TO_STUB[@]}")

usage() {
  cat <<-EOF
Usage:  $(basename "${BASH_SOURCE[0]}") [path-to-intellij-lib-dir]

Updates IntelliJ jars from a local IntelliJ installation.
EOF
}

if [[ "$1" == -h ]] || [[ "$1" == --help ]] || [[ "$1" == help ]]; then
  usage
  exit 0
fi

DEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUCK_FILE="$DEST_DIR/BUCK"


SRC_DIR="$1"
# If src unspecified on OSX, and the usual location exists, ask to use it
if [[ -z "$SRC_DIR" ]]; then
  if [[ "$OSTYPE" == darwin* ]]; then
    DEFAULT_LOCATION="/Applications/IntelliJ IDEA CE.app/Contents/lib"
    if [[ -d "$DEFAULT_LOCATION" ]]; then
      read -r -p "Copy from IntelliJ installation at '$DEFAULT_LOCATION'? [Y/n] " response
      if [[ -z "$response" ]] || [[ "$response" =~ ^[Yy] ]]; then
        SRC_DIR="$DEFAULT_LOCATION"
      fi
    fi
  fi
  if [[ -z "$SRC_DIR" ]]; then
    echo "ERROR: No IntelliJ installation specified."
    usage >&2
    exit 1
  fi
fi


# ====================================================================
#  Copy and/or stub jars
# ====================================================================
printf "Copying IntelliJ jars from %q\n" "$SRC_DIR"

SHOULD_FAIL=false
for lib in "${ALL_LIBS[@]}"; do
  if ! [[ -e "$SRC_DIR/$lib" ]]; then
    >&2 echo "ERROR:  Can't find required library $lib in $SRC_DIR"
    SHOULD_FAIL=true
  fi
done
if $SHOULD_FAIL ; then
  echo "Aborting."
  exit 1
fi

for lib in "${LIBS_TO_COPY[@]}"; do
  echo "Copying $lib..."
  cp "$SRC_DIR/$lib" "$DEST_DIR/$lib"
done
for lib in "${LIBS_TO_STUB[@]}"; do
  if [[ -e "$DEST_DIR/$lib" ]]; then
    printf "Removing previous $lib...  "
    rm "$DEST_DIR/$lib"
  fi
  echo "Stubbing $lib..."
  if ! (cd "$DEST_DIR" && 2>/dev/null buck run //src/com/facebook/buck/jvm/java/abi:api-stubber "$SRC_DIR/$lib" "$DEST_DIR/$lib") ; then
    echo "ERROR:  Failed to make stubbed copy of $lib"
    SHOULD_FAIL=true
  fi
done
if $SHOULD_FAIL ; then
  echo "Aborting."
  exit 1
fi

# ====================================================================
#  Sanity check against BUCK file that we:
#    (1) want what we got
#    (2) got what we want
# ====================================================================
for lib in "${ALL_LIBS[@]}"; do
  if ! grep -q "binary_jar.*=.*\W${lib}\W" "$BUCK_FILE" ; then
    echo "Warning:  the file '$lib' was copied, but it is not mentioned by any rule in BUCK"
    SHOULD_FAIL=true
  fi
done

grep binary_jar "$BUCK_FILE" | cut -d"'" -f2 | sort | uniq | while IFS= read -r wanted ; do
  FOUND=false
  for copied in "${ALL_LIBS[@]}"; do
    if [[ "$wanted" == "$copied" ]]; then
      FOUND=true
      break
    fi
  done
  if ! $FOUND ; then
    echo "Warning:  '$wanted' is mentioned in BUCK, but it was not copied/stubbed."
    SHOULD_FAIL=true
  fi
done

if $SHOULD_FAIL; then
  exit 1
fi

echo "Copy successful."

RESOURCES_JAR="$SRC_DIR/resources.jar"
if [[ -e "$RESOURCES_JAR" ]]; then
  INFO_FILE="idea/IdeaApplicationInfo.xml"
  INTELLIJ_INFO="$(unzip -p "$RESOURCES_JAR" "$INFO_FILE")"
  if [[ -n "$INTELLIJ_INFO" ]]; then
     printf "Version/build info from %q!%q\n" "$RESOURCES_JAR" "$INFO_FILE"
     echo "$INTELLIJ_INFO" | grep "version.*major"
     echo "$INTELLIJ_INFO" | grep "build number"
     echo "Please note these in your commit message."
  fi
fi
