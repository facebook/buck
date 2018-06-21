#!/usr/bin/python -u

import json
import optparse
import subprocess
import sys

parser = optparse.OptionParser()
parser.add_option("--buck-test-info")
parser.add_option("--jobs", type=int)
(options, args) = parser.parse_args()


for arg in args:
    print(arg)
