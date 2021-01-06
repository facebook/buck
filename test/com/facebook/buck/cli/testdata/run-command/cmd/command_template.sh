#!/bin/sh

echo "CONFIG = <<CONFIG>>"

echo PWD: `pwd` > /tmp/test-command-output
echo $1 > $2

echo "SUCCESS"

exit 0
