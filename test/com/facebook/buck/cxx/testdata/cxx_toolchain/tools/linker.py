import sys

import impl

parser = impl.argparser()

parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

# ranlib hid the archive next to what buck thinks the archive is
archive = args[-1] + ".secret"

with open(options.output, "w") as output:
    output.write("linker:\n")
    with open(archive) as archivefile:
        output.write(archivefile.read())

sys.exit(0)