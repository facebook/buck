import os

want = "hello world!\n"

got = __loader__.get_data(
    os.path.join(os.path.dirname(__file__), "__cxx_resources__", "blah", "foo.dat")
)

assert want == got, "expected {}, got {}".format(repr(want), repr(got))
