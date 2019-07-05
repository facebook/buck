""" Module docstring """

def _impl(_ctx):
    print("printing at debug level")

my_rule = rule(
    attrs = {
    },
    implementation = _impl,
)
