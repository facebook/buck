#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()
(options, args) = parser.parse_known_args()

line1 = sys.stdin.readline()
line2 = sys.stdin.readline()

assert line1.startswith("target create")
assert line2.startswith("target symbols add")

binary = line1.split()[-1]
dsym = line2.split()[-1]

assert os.path.exists(dsym)

with open(binary, "a") as binfile:
    binfile.write("lldb: " + dsym)

sys.exit(0)
