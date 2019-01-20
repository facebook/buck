#!/bin/bash

# Writes an error message and exits with non-zero exit code, so that buck
# output captures the message

>&2 echo "codesign was here"
exit 1
