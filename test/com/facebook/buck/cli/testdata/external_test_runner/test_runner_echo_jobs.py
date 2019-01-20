#!/usr/bin/python -u

import optparse

parser = optparse.OptionParser()
parser.add_option("--buck-test-info")
parser.add_option("--jobs", type=int)
(options, args) = parser.parse_args()


print(options.jobs)
