import sys

import impl

parser = impl.argparser()
(options, args) = parser.parse_known_args()

output = args[1]
inputs = args[2:]

# Just do some simple verification of the args. Check that the extensions are as we configured.
assert output.endswith(".static")
assert len(inputs) == 2
assert inputs[0].endswith(".object")
assert inputs[1].endswith(".object")

with open(output, "w") as archive:
    archive.write("archive:\n")
    for input in inputs:
        archive.write("object: ")
        with open(input) as inputfile:
            archive.write(inputfile.read())
        archive.write("\n")

sys.exit(0)