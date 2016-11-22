#!/bin/bash
THIS_DIR=$(pwd)/js

get_param() {
  PARAM_STRING="$1"
  PARAM_NAME="$2"
  echo $(echo $PARAM_STRING | sed "s/.*--$PARAM_NAME \([^ ]*\).*/\1/")
}

is_flag_enabled() {
  PARAM_STRING="$1"
  FLAG_NAME="$2"
  for PARAM_NAME in $PARAM_STRING; do
    [[ "$PARAM_NAME" == "$FLAG_NAME" ]] && return 0
  done
  return 1
}

reply_success() {
  MESSAGE_ID="$1"
  printf ",{\"id\":%s, \"type\":\"result\", \"exit_code\":0}" "$MESSAGE_ID"
}

copy_resources() {
  OUTPUT_DIR="$1"
  mkdir $OUTPUT_DIR/drawable-mdpi
  cp "$THIS_DIR/app/image@1.5x.png" $OUTPUT_DIR/drawable-mdpi/image.png
  mkdir $OUTPUT_DIR/drawable-hdpi
  cp "$THIS_DIR/app/image@2x.png" $OUTPUT_DIR/drawable-hdpi/image.png
  mkdir $OUTPUT_DIR/drawable-xhdpi
  cp "$THIS_DIR/app/image@3x.png" $OUTPUT_DIR/drawable-xhdpi/image.png
}

# Read in the handshake JSON.
read -d "}" handshake_json
# Extract the id value.
handshake_id=$(echo "$handshake_json" | sed 's/.*"id":\([0-9]*\).*/\1/')
# Send the handshake reply.
printf "[{\"id\":%s, \"type\":\"handshake\", \"protocol_version\":\"0\", \"capabilities\": []}" "$handshake_id"

# Expect two jobs, one for the dependencies, and one for the bundle/unbundle.
for ((i=1; i <= 2 ; i++))
do
  # Read in the job JSON.
  read -d "}" job_json
  # Check if the stream has been closed and exit if so
  if [[ $job_json == "]" ]]
  then
    echo "]"
    exit 0
  fi
  # Extract the id value.
  message_id=$(echo "$job_json" | sed 's/.*"id":\([0-9]*\).*/\1/')
  # Extract the path to the file containing the job args.
  args_path=$(echo "$job_json" | sed 's/.*"args_path":"\([^"]*\)",.*/\1/')
  # Read the job args from the args file.
  args_string=$(cat "$args_path")

  command=$(get_param "$args_string" "command")
  case "$command" in
  'bundle')
    ASSETS_DEST=$(get_param "$args_string" "assets-dest")
    BUNDLE_OUTPUT=$(get_param "$args_string" "bundle-output")
    SOURCEMAP_OUTPUT=$(get_param "$args_string" "sourcemap-output")

    cat $THIS_DIR/app/sample.android.js $THIS_DIR/app/helpers.js > $BUNDLE_OUTPUT
    copy_resources $ASSETS_DEST

    # write something as the source map because the rule caches this output.
    echo "sourcemap" > "$SOURCEMAP_OUTPUT"
    reply_success $message_id
    ;;
  'unbundle')
    ASSETS_DEST=$(get_param "$args_string" "assets-dest")
    SOURCEMAP_OUTPUT=$(get_param "$args_string" "sourcemap-output")
    BUNDLE_OUTPUT=$(get_param "$args_string" "bundle-output")

    cp $THIS_DIR/app/sample.android.js "$BUNDLE_OUTPUT"

    is_flag_enabled "$args_string" "--indexed-unbundle" || {
      JS_MODULE_DIR=`dirname "$BUNDLE_OUTPUT"`/js
      mkdir "$JS_MODULE_DIR"
      cp $THIS_DIR/app/helpers.js "$JS_MODULE_DIR/helpers.js"
    }

    copy_resources $ASSETS_DEST

    # write something as the source map because the rule caches this output.
    echo "sourcemap" > "$SOURCEMAP_OUTPUT"
    reply_success $message_id
    ;;
  'dependencies')
    OUTPUT_FILE=$(get_param "$args_string" "output")
    echo $THIS_DIR/app/sample.android.js > $OUTPUT_FILE
    echo $THIS_DIR/app/helpers.js >> $OUTPUT_FILE
    echo $THIS_DIR/app/image@1.5x.png >> $OUTPUT_FILE
    echo $THIS_DIR/app/image@2x.png >> $OUTPUT_FILE
    echo $THIS_DIR/app/image@3x.png >> $OUTPUT_FILE
    reply_success $message_id
    ;;
  *)
    echo "Invalid command"
    exit 1
  esac

done

# Read in the end of the JSON array and reply with a corresponding closing bracket.
read -d "]"
echo ]
