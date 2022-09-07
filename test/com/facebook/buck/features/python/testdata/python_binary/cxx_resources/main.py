import os
import sys

want = "hello world!\n"

got = __loader__.get_data(
    os.path.join(os.path.dirname(__file__), "__cxx_resources__", "blah", "foo.dat")
)

if sys.version_info >= (3,):
    got = got.decode("utf-8")

assert want == got, "expected {}, got {}".format(repr(want), repr(got))
