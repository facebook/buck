import os

assert (
    __loader__.get_data(
        os.path.join(os.path.dirname(__file__), "__cxx_resources__", "blah", "foo.dat")
    )
    == "hello world!\n"
)
