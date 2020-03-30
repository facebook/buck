""" Module docstring """

def _impl(_ctx):
    """ Function docstring """
    pass

some_rule = rule(
    attrs = {
        "attr1": attr.int(
            default = 1,
            mandatory = True,
        ),
        "attr2": attr.int(
            default = 2,
            mandatory = False,
        ),
    },
    implementation = _impl,
)

def macro(name, attr1, attr2):
    some_rule(name = name, attr1 = attr1, attr2 = attr2)
