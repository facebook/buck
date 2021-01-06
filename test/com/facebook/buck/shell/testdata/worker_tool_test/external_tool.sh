#!/bin/bash

for i in "$@"; do
# Extract the "--num-jobs <n>" value from the args. This value is the number of
# jobs this script should expect to be sent to it from buck.
    case $i in
        --num-jobs)
            num_jobs="$2"
            shift
            ;;
# Extract the "--loc <file>" value from the args. This value is a path that
# should exist.
        --loc)
            loc="$2"
            shift
            ;;
        --async)
            async="$2"
            shift
            ;;
        *)
            shift
            ;;
    esac
done

ARGS="--num-jobs $num_jobs"
if [ "$loc" != "" -a ! -e "$loc" ]; then
    echo "$loc does not exist" 1>&2
    exit 1
elif [ "$loc" != "" ]; then
  ARGS="$ARGS --loc exists"
fi

# Read in the handshake JSON.
read -d "}" handshake_json
# Extract the id value.
handshake_id=$(echo "$handshake_json" | sed 's/.*"id":\([0-9]*\).*/\1/')
# Send the handshake reply.
printf "[{\"id\":%s, \"type\":\"handshake\", \"protocol_version\":\"0\", \"capabilities\": []}" "$handshake_id"

for ((i=1; i <= num_jobs ; i++))
do
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
  echo "the startup arguments were: $ARGS" > $output_path
  # Send the job result reply.
  if [ "$async" != "" ]; then
      printf ",{\"id\":%s, \"type\":\"result\", \"exit_code\":0}" "$message_id" >> $TMP/output
  else
      printf ",{\"id\":%s, \"type\":\"result\", \"exit_code\":0}" "$message_id"
  fi
done

if [ "$async" != "" ]; then
    cat $TMP/output
fi
# Read in the end of the JSON array and reply with a corresponding closing bracket.
read -d "]"
echo ]
