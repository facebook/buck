import shutil
import sys

import impl

parser = impl.argparser()

parser.add_argument("--strip-unneeded", action="store_true")
parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

# the single unknown arg should be the input
library = args[0]

with open(options.output, "w") as output:
    output.write("strip:\n")
    with open(library) as archivefile:
        output.write(archivefile.read())

sys.exit(0)