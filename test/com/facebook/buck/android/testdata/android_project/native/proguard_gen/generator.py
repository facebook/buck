#!/usr/bin/python3

import sys


assert len(sys.argv) > 2
with open(sys.argv[1], "w") as out:
    for l in sys.argv[2:]:
        out.write("# %s\n" % l)
