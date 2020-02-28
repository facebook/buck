#!/usr/bin/env python3

import hashlib
import shutil
import sys

from tools import impl


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

# Calculate hash of the real output so fake output is not always the same.
sha1 = hashlib.sha1()
with open(secret, "rb") as secretfile:
    sha1.update(secretfile.read())

# Ranlib entry format:
# Entry size 60 bytes
# 16 bytes filename
# 12 bytes timestamp
# 6 bytes owner id
# 6 bytes group id
# 8 bytes file mode
# 10 bytes file size
# 2 bytes magic (0x60, 0x0a)
with open(archive, "wb") as output:
    output.write(
        b"!<arch>\n" + sha1.hexdigest().encode()[:16] + b"\x00" * 42 + b"\x60\x0a"
    )

sys.exit(0)
