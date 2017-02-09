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

run_command() {
  local message_id="$1"

  set -- $2
  local args_string=${@:1:$(($#-2))}

  set -- ${@: -2}
  local infile="$1"
  local outfile="$2"
  mkdir -p "$(dirname "$outfile")"

  # first line are arguments passed in
  echo "$args_string" > "$outfile"

  # append input file contents
  cat "$infile" >> "$outfile"

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