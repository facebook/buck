#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-test-arg", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

assert os.path.exists(options.test_arg), options.test_arg

# ranlib may have hid the archive next to what buck thinks the archive is
input = args[-1] + ".secret"

if not os.path.exists(input):
    input = args[-1]

with open(options.output, "w") as output:
    output.write("linker:\n")
    with open(input) as inputfile:
        output.write(inputfile.read())

sys.exit(0)
