#!/bin/bash

# Read in the handshake JSON.
read -d "}" handshake_json
# Extract the id value.
handshake_id=$(echo "$handshake_json" | sed 's/.*"id":\([0-9]*\).*/\1/')
# Send the handshake reply.
printf "[{\"id\":%s, \"type\":\"handshake\", \"protocol_version\":\"0\", \"capabilities\": []}" "$handshake_id"

while read -n1 -d ] next_char; do
  if [ "," == "$next_char" ]; then
    # Read in the job JSON.
    read -d "}" job_json
    # Extract the id value.
    message_id=$(echo "$job_json" | sed 's/.*"id":\([0-9]*\).*/\1/')
    # Extract the path to the file containing the job args.
    args_path=$(echo "$job_json" | sed 's/.*"args_path":"\([^"]*\)",.*/\1/')
    # Read the job args from the args file. This assumes the args file only
    # contains the path for the output file.
    output_path=$(cat "$args_path")
    # Write to the output file.
    echo $$ > $output_path
    # Send the job result reply after blocking for some time.
    sleep 1
    printf ",{\"id\":%s, \"type\":\"result\", \"exit_code\":0}" "$message_id"
  fi
done
echo ]
