#!/usr/bin/env python3

import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("-create", action="store_true")
parser.add_argument("-output", dest="output", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

assert options.create

with open(options.output, "w") as output:
    for file in args:
        output.write("universal file:\n")
        with open(file, "r") as input:
            output.write(input.read())

sys.exit(0)
