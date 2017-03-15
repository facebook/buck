#!/bin/bash
set -e

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

run_command() {
  local args=
  local infiles=
  local outfile=
  local message_id="$1"

  set -- $2

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --out)
        outfile="$2"
        shift
        ;;
      --lib)
        args=$(concat $args "$1" "${2/\/*\/buck-out\//@/buck-out/}")
        infiles=$(concat $infiles "$2")
        shift
        ;;
      --*)
        args=$(concat $args "$1")
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

  if [[ -z "$outfile" ]]; then
    echo "No output file given" >&2
    return ',{"id": %d, "type": "error", "exit_code": 1}' "$message_id"
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