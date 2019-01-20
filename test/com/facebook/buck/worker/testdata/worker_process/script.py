#!/usr/bin/env python

import sys

# Write 1MiB of nonsense to check that Buck can handle large stderr correctly.
for i in range(1024):
    sys.stderr.write("a" * 1024 + "\n")
    sys.stderr.flush()

# Write invalid JSON to stdout to trigger an exception when Buck tries to
# consume our output.
sys.stdout.write("}")
sys.stdout.flush()

# Consume all of stdin to avoid a race with Buck trying to talk to us.
while sys.stdin.read():
    pass
