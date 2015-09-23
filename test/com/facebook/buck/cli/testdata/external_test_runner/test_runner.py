#!/usr/bin/python -u

import json
import optparse
import sys
import subprocess


parser = optparse.OptionParser()
parser.add_option('--buck-test-info')
(options, args) = parser.parse_args()


if options.buck_test_info is not None:
    with open(options.buck_test_info) as f:
        test_infos = json.load(f)
        for info in test_infos:
            subprocess.call(info['command'])
