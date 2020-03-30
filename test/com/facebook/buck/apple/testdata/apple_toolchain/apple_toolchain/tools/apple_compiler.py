#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()
# We just need to know the input file, the output location and the depfile location
parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-MF", dest="depfile", action=impl.StripQuotesAction)
parser.add_argument("-test-flag", action=impl.StripQuotesAction)
parser.add_argument("-extra-compile-flag", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

impl.log(options)
impl.log(args)

# input file appears last in the command
input = args[-1]

# We configured the toolchain to use .object as object file extension
assert options.output.endswith(".object")

assert os.path.exists(options.test_flag), options.test_flag
assert os.path.exists(options.extra_compile_flag), options.extra_compile_flag

with open(input) as inputfile:
    data = inputfile.read()

with open(options.output, "w") as out:
    out.write("compile output: " + data)

with open(options.depfile, "w") as depfile:
    depfile.write(options.output + " :\\\n    " + input + "\n")

sys.exit(0)
