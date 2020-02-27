#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()
# We just need to know the input file, the output location and the depfile location
parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-MF", dest="depfile", action=impl.StripQuotesAction)
parser.add_argument("-test-flag", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

test_content = None
if options.test_flag:
    assert os.path.exists(options.test_flag), options.test_flag
    with open(options.test_flag, "r") as f:
        test_content = f.read()

# input file appears last in the command
input = args[-1]

# We configured the toolchain to use .object as object file extension
assert options.output.endswith(".object")

with open(input) as inputfile:
    data = inputfile.read()

with open(options.output, "w") as out:
    if test_content:
        out.write("compiler test content: " + test_content)
    out.write("compile output: " + data)

with open(options.depfile, "w") as depfile:
    depfile.write(options.output + " :\\\n    " + input + "\n")

sys.exit(0)
