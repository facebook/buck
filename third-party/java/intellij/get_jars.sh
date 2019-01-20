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
INTELLIJ_LIBS_TO_COPY=(
  annotations.jar
  extensions.jar
  java-api.jar
  java-impl.jar
  jdom.jar
  openapi.jar
  picocontainer.jar
  platform-api.jar
  platform-impl.jar
  serviceMessages.jar
  util.jar
)
INTELLIJ_LIBS_TO_STUB=(
  idea.jar
  java-impl.jar
  platform-impl.jar
)
INTELLIJ_LIBS=("${INTELLIJ_LIBS_TO_COPY[@]}" "${INTELLIJ_LIBS_TO_STUB[@]}")

ANDROID_LIBS_TO_COPY=(
)
ANDROID_LIBS_TO_STUB=(
  android.jar
)
ANDROID_LIBS=("${ANDROID_LIBS_TO_COPY[@]}" "${ANDROID_LIBS_TO_STUB[@]}")

ALL_LIBS=("${INTELLIJ_LIBS[@]}" "${ANDROID_LIBS[@]}")

usage() {
  cat <<-EOF
Usage:  $(basename "${BASH_SOURCE[0]}") [path-to-intellij-lib-dir]

Updates IntelliJ jars from a local IntelliJ installation.
EOF
}

copy_or_stub_jars() {
  if [ "$#" -lt 3 ]; then
    >&2 echo "ERROR:  copy_or_stub_jars() requires parameters: SOURCE_DIR, # of LIBS_TO_COPY, LIBS_TO_COPY..., # of LIBS_TO_STUB, LIBS_TO_STUB..."
    SHOULD_FAIL=true
  fi

  SHOULD_FAIL=false
  SOURCE_DIR=$1; shift
  NUM_LIBS_TO_COPY=$1; shift
  LIBS_TO_COPY=("${@:1:$NUM_LIBS_TO_COPY}"); shift $NUM_LIBS_TO_COPY
  NUM_LIBS_TO_STUB=$1; shift
  LIBS_TO_STUB=("${@:1:$NUM_LIBS_TO_STUB}")
  LIBS=("${LIBS_TO_COPY[@]}" "${LIBS_TO_STUB[@]}")

  for lib in "${LIBS[@]}"; do
    if ! [[ -e "$SOURCE_DIR/$lib" ]]; then
      >&2 echo "ERROR:  Can't find required library $lib in $SOURCE_DIR"
      SHOULD_FAIL=true
    fi
  done
  if $SHOULD_FAIL ; then
    echo "Aborting."
    exit 1
  fi

  for lib in "${LIBS_TO_COPY[@]}"; do
    echo "Copying $lib..."
    cp "$SOURCE_DIR/$lib" "$DEST_DIR/$lib"
  done
  for lib in "${LIBS_TO_STUB[@]}"; do
    if [[ -e "$DEST_DIR/$lib" ]]; then
      printf "Removing previous $lib...  "
      rm "$DEST_DIR/$lib"
    fi
    echo "Stubbing $lib..."
    if ! (cd "$DEST_DIR" && 2>/dev/null buck run //src/com/facebook/buck/jvm/java/abi:api-stubber "$SOURCE_DIR/$lib" "$DEST_DIR/$lib") ; then
      echo "ERROR:  Failed to make stubbed copy of $lib"
      SHOULD_FAIL=true
    fi
  done
  if $SHOULD_FAIL ; then
    echo "Aborting."
    exit 1
  fi
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
copy_or_stub_jars "$SRC_DIR" \
    ${#INTELLIJ_LIBS_TO_COPY[@]} ${INTELLIJ_LIBS_TO_COPY[@]} \
    ${#INTELLIJ_LIBS_TO_STUB[@]} ${INTELLIJ_LIBS_TO_STUB[@]}

ANDROID_SRC_DIR="$SRC_DIR/../plugins/android/lib"
printf "Copying IntelliJ Android jars from %q\n" "$ANDROID_SRC_DIR"
copy_or_stub_jars "$ANDROID_SRC_DIR" \
    ${#ANDROID_LIBS_TO_COPY[@]} ${ANDROID_LIBS_TO_COPY[@]} \
    ${#ANDROID_LIBS_TO_STUB[@]} ${ANDROID_LIBS_TO_STUB[@]}

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

grep binary_jar "$BUCK_FILE" | cut -d'"' -f2 | sort | uniq | while IFS= read -r wanted ; do
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
