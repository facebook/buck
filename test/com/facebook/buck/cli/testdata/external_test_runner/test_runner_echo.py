#!/usr/bin/python -u

import json
import optparse
import sys
import subprocess


parser = optparse.OptionParser()
parser.add_option('--buck-test-info')
(options, args) = parser.parse_args()


for arg in args:
    print(arg)
