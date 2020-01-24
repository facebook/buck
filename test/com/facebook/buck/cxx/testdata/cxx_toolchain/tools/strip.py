#!/usr/bin/env python3

import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("--strip-unneeded", action="store_true")
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

with open(output, "w") as output:
    output.write("strip:\n")
    output.write(content)

sys.exit(0)
