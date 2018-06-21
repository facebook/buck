#!/usr/bin/env python

from __future__ import print_function

import argparse
import os

parser = argparse.ArgumentParser(description="Test")
parser.add_argument("--output-dir", dest="output_dir", action="store")


args = parser.parse_args()
with open(os.path.join(args.output_dir, "extra.txt"), "w") as o:
    o.write("data")

print("Extra")
