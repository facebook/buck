#!/bin/bash
set -e

ROOT="$PWD"

message_id () {
  echo "$1" | sed 's/.*"id":\([0-9]*\).*/\1/'
}

handshake() {
  # Read in the handshake JSON.
  local handshake_json
  read -d "}" handshake_json
  # Extract the id value.
  local handshake_id=$(message_id "$handshake_json")
  # Send the handshake reply.
  printf "[{\"id\":%s, \"type\":\"handshake\", \"protocol_version\":\"0\", \"capabilities\": []}" "$handshake_id"
}

read_command() {
  local job_json
  read -d "}" job_json

  if [[ -n "$job_json"  && "$job_json" != "]" ]]; then

    # Extract the id value.
    local message_id=$(message_id "$job_json")
    # Extract the path to the file containing the job args.
    local args_path=$(echo "$job_json" | sed 's/.*"args_path":"\([^"]*\)",.*/\1/')

    echo "$message_id"
    cat "$args_path"

  fi
}

concat() {
  echo $@
}

write_asset() {
  local dir="$1"
  if [[ "$2" == android ]]; then
    dir="$dir/drawable-mdpi"
    mkdir -p "$dir"
  elif [[ "$2" == ios ]]; then
    dir="$dir/assets"
    mkdir -p "$dir"
  fi
  cp "$(dirname "$0")/pixel.gif" "$dir/"
}

write_sourcemap() {
    echo '{"version": 3, "mappings": "ABCDE;"}' > "$1"
}

replace_root() {
  if [[ "$1" == "$ROOT" ]]; then
    echo @
  else
    echo "${1/$ROOT\//@/}"
  fi
}

run_command() {
  local args=
  local infiles=
  local outfile=
  local message_id="$1"
  local platform=
  local assets_dir=

  # While we transition to JSON, buck can generates both format depending on the type of rule.
  if [[ "$2" == '{'* ]]; then

    # Process args_string ($2) as JSON
    # We use `sed` to extract JSON field values. This is utterly broken, as it doesn't handle
    # escaping, spaces, etc. However, it gets the job done here by assuming we won't have escaped
    # characters in integration tests, and that the JSON is minified.

    # We just 'relativize' any path contained anywhere in the JSON args. This will break
    # if $ROOT contain any pipe character "|", that we use as `sed` delimiter.
    args=$(echo "$2" | sed "s|$ROOT|@|g")

    local command=$(echo "$2" | sed -n 's/.*"command":"\([^"]*\)".*/\1/p')

    if [[ "$command" == "bundle" ]]; then
      infiles=$(echo "$2" | sed -n 's/.*"libraries":\["\([^]]*\)"\].*/\1/p' | sed 's/","/ /g')
      outfile=$(echo "$2" | sed -n 's/.*"bundlePath":"\([^"]*\)".*/\1/p')
      platform=$(echo "$2" | sed -n 's/.*"platform":"\([^"]*\)".*/\1/p')
      assets_dir=$(echo "$2" | sed -n 's/.*"assetsDirPath":"\([^"]*\)".*/\1/p')

      local source_map_path=$(echo "$2" | sed -n 's/.*"sourceMapPath":"\([^"]*\)".*/\1/p')
      if [[ -n "$source_map_path" ]]; then
        write_sourcemap "$source_map_path"
      fi
    fi

    if [[ "$command" == "dependencies" ]]; then
      infiles=$(echo "$2" | sed -n 's/.*"libraries":\["\([^]]*\)"\].*/\1/p' | sed 's/","/ /g')
      outfile=$(echo "$2" | sed -n 's/.*"outputFilePath":"\([^"]*\)".*/\1/p')
    fi

    if [[ "$command" == "library-dependencies" ]]; then
      infiles=$(echo "$2" | sed -n 's/.*"aggregatedSourceFilesFilePath":"\([^"]*\)".*/\1/p')
      local deps=$(echo "$2" | sed -n 's/.*"dependencyLibraryFilePaths":\["\([^]]*\)"\].*/\1/p' | sed 's/","/ /g')
      infiles="$deps $infiles"
      outfile=$(echo "$2" | sed -n 's/.*"outputPath":"\([^"]*\)".*/\1/p')
    fi

    if [[ "$command" == "library-files" ]]; then
      infiles=$(echo "$2" | sed -n 's/.*"sourceFilePaths":\["\([^]]*\)"\].*/\1/p' | sed 's/","/ /g')
      outfile=$(echo "$2" | sed -n 's/.*"outputFilePath":"\([^"]*\)".*/\1/p')
    fi

    if [[ "$command" == "transform" ]]; then
      infiles=$(echo "$2" | sed -n 's/.*"sourceJsFilePath":"\([^"]*\)".*/\1/p')
      outfile=$(echo "$2" | sed -n 's/.*"outputFilePath":"\([^"]*\)".*/\1/p')
    fi

    if [[ "$command" == "optimize" ]]; then
      infiles=$(echo "$2" | sed -n 's/.*"transformedJsFilePath":"\([^"]*\)".*/\1/p')
      outfile=$(echo "$2" | sed -n 's/.*"outputFilePath":"\([^"]*\)".*/\1/p')
    fi

  else

    # Process args_string ($2) as command-line arguments
    set -- $2

    while [[ $# -gt 0 ]]; do
      case "$1" in
        --out)
          outfile="$2"
          shift
          ;;
        --assets|--root|--sourcemap)
          args=$(concat $args "$1" "$(replace_root "$2")")
          if [[ "$1" == "--assets" ]]; then
            assets_dir="$2"
          elif [[ "$1" == "--sourcemap" ]]; then
            write_sourcemap "$2"
          fi
          shift
          ;;
        --lib|--files)
          args=$(concat $args "$1" "$(replace_root "$2")")
          infiles=$(concat $infiles "$2")
          shift
          ;;
        --*)
          args=$(concat $args "$1")
          if [[ "$1" == "--platform" ]]; then
            platform="$2"
          fi
          if [[ "$2" != --* ]]; then
            args=$(concat $args "$2")
            shift
          fi
          ;;
        */*)
          infiles=$(concat $infiles "$1")
          ;;
        *)
          args=$(concat $args "$1")
          ;;
      esac
      shift
    done
  fi

  if [[ -n "$assets_dir" ]]; then
    write_asset "$assets_dir" "$platform"
  fi

  if [[ -z "$outfile" ]]; then
    echo "No output file given" >&2
    printf ',{"id": %d, "type": "error", "exit_code": 1}' "$message_id"
    return
  fi

  mkdir -p "$(dirname "$outfile")"

  # first line are arguments passed in
  echo "$args" > "$outfile"

  # append input file contents
  local infile
  for infile in $infiles; do
    cat "$infile" >> "$outfile"
  done

  printf ',{"id": %d, "type": "result", "exit_code": 0}' "$message_id"
}

command_loop() {
  local data=$(read_command)
  until [[ -z "$data" ]]; do
    local message_id=$(echo "$data" | head -n 1)
    local args_string=$(echo "$data" | tail -n +2)
    run_command "$message_id" "$args_string"
    data=$(read_command)
  done
}

shutdown() {
  echo "]"
}

handshake
command_loop
shutdown
