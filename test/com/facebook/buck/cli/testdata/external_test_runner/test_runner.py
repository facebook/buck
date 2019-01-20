#!/usr/bin/python -u

from __future__ import print_function

import json
import optparse
import subprocess
import sys

parser = optparse.OptionParser()
parser.add_option("--buck-test-info")
parser.add_option("--jobs", type=int)
(options, args) = parser.parse_args()


if options.buck_test_info is not None:
    with open(options.buck_test_info) as f:
        test_infos = json.load(f)
        for info in test_infos:
            subprocess.call(info["command"])
