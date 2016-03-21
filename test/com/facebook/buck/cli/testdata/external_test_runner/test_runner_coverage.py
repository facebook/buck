#!/usr/bin/python -u

from __future__ import print_function

import json
import optparse
import sys
import subprocess


parser = optparse.OptionParser()
parser.add_option('--buck-test-info')
(options, args) = parser.parse_args()


with open(options.buck_test_info) as f:
    test_infos = json.load(f)
    print(test_infos[0]['needed_coverage'])
