#!/usr/bin/env python

import sys

# Write 1MiB of nonsense to check that Buck can handle large stderr correctly
for i in range(1024):
    sys.stderr.write('a' * 1024 + '\n')
    sys.stderr.flush()
