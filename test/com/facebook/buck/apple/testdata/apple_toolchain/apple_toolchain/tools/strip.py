#!/usr/bin/env python3

import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("-r", action="store_true")
parser.add_argument("-S", action="store_true")
parser.add_argument("-T", action="store_true")
parser.add_argument("-u", action="store_true")
parser.add_argument("-x", action="store_true")
parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

# the single unknown arg should be the input
library = args[0]

with open(library) as archivefile:
    content = archivefile.read()

output = options.output
# support in-place mode
if output is None:
    output = library

flags = ""
if options.r:
    flags += "r"
if options.S:
    flags += "S"
if options.T:
    flags += "T"
if options.u:
    flags += "u"
if options.x:
    flags += "x"

with open(output, "w") as output:
    output.write(f"strip {flags}:\n")
    output.write(content)

sys.exit(0)
