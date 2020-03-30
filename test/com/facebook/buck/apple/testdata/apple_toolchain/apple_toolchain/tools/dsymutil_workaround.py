#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("-o", dest="output_dir", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

assert "-num-threads=1" in args
args.remove("-num-threads=1")

binary = args[0]
output_dir = os.path.join(options.output_dir, "Contents", "Resources", "DWARF")
os.makedirs(output_dir)
output_file = os.path.join(output_dir, os.path.basename(binary))

with open(output_file, "w") as output:
    output.write("dsymutil workaround:\n")
    with open(binary, "r") as binfile:
        output.write(binfile.read())

sys.exit(0)
