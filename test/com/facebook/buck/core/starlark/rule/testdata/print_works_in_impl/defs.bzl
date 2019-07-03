""" Module docstring """

def _impl(_ctx):
    print("here")

my_rule = rule(
    attrs = {
    },
    implementation = _impl,
)
