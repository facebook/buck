#!/usr/bin/env python3

import sys


sys.stderr.buffer.write(b"\xe2" * 2048)
sys.stderr.buffer.write("foo bar baz".encode("utf8"))
sys.stderr.flush()
sys.exit(1)
