#!/usr/bin/env python3

from __future__ import print_function

import os
import sys


out = sys.argv[1]
parent = os.path.dirname(out)

print("Touching {}".format(out), file=sys.stderr)

if not os.path.exists(os.path.dirname(out)):
    os.makedirs(os.path.dirname(out))

with open(out, "w"):
    pass

print("Touched {}".format(out), file=sys.stderr)
