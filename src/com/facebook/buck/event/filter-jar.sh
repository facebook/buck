#!/bin/bash
#
# Hack to remove non-Buck code from the event-deps jar.
#

log() {
  echo "$0: $*"
}

if [ -z "$1" -o -z "$2" ]
then
  echo >&2 "usage: $(dirname "$0") <input-jar> <path-to-output-jar>"
  exit 2
fi

old_jar="$1"
new_jar="$2"

if [ ! -e "$old_jar" ]
then
  log "can't find input jar '$old_jar'"
  exit 2
fi

if [ ! -e "$(dirname "$new_jar")" ]
then
  log "directory in which '$new_jar' will be created doesn't exist!"
  exit 2
fi

log "starting jar filtering..."

set -e

mkdir "$TMP/old"
mkdir "$TMP/new"

#
# Unpack the .jar we built
#
cp "$old_jar" "$TMP/old"
(
  log "expanding $old_jar"
  cd "$TMP/old"
  jar -xf *.jar
)

#
# Copy over some boilerplate
#
for item in LICENSE META-INF
do
  cp -r "$TMP/old/$item" "$TMP/new/"
done

#
# Copy over the facebook.com code
#
mkdir "$TMP/new/com"
cp -r "$TMP/old/com/facebook" "$TMP/new/com/"

#
# Rebuild a new jar
#
log "packing $new_jar"
jar -cf "$new_jar" -C "$TMP/new" .
