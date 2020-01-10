import sys

import impl
from impl import StripQuotesAction

parser = impl.argparser()
# We just need to know the input file, the output location and the depfile location
parser.add_argument("-o", dest="output", action=StripQuotesAction)
parser.add_argument("-MF", dest="depfile", action=StripQuotesAction)

(options, args) = parser.parse_known_args()

impl.log(options)
impl.log(args)

# input file appears last in the command
input = args[-1]

# We configured the toolchain to use .object as object file extension
assert options.output.endswith(".object")

with open(input) as inputfile:
    data = inputfile.read()

with open(options.output, "w") as out:
    out.write("compile output: " + data)

with open(options.depfile, "w") as depfile:
    depfile.write(options.output + " :\\\n    " + input + "\n")

sys.exit(0)