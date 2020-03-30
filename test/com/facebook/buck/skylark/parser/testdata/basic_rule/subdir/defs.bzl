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
    },
    implementation = _impl,
)

some_other_rule = rule(
    attrs = {
        "attr2": attr.int(
            default = 1,
            mandatory = True,
        ),
    },
    implementation = _impl,
)

def macro(name, attr1):
    some_rule(name = name, attr1 = attr1)
