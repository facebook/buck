#!/bin/bash

while test $# -gt 0; do
  if [[ "$1" == "--buck-test-info" ]]; then file=$2; fi
  shift
done

external_runner_config=$(cat $file)
type="my-special-unique-custom-type"

if [[ "${external_runner_config#*$type}" != "$external_runner_config" ]]; then
  exit 0
else
  exit 1
fi