import shutil
import sys

import impl

parser = impl.argparser()
(options, args) = parser.parse_known_args()

impl.log(args)

# ranlib just gets a single argument and is expected to run in-place
archive = args[0]

assert archive.endswith(".static")

# Buck requires that our output at this point is a real archive so that it can run scrubbers. We'll
# move our file off to the side and create a trivial archive here.

secret = archive + ".secret"
shutil.copy(archive, secret)
with open(secret, "a") as secretfile:
    secretfile.write("ranlib applied.\n")

with open(archive, "wb") as output:
    output.write("!<arch>\n")

sys.exit(0)