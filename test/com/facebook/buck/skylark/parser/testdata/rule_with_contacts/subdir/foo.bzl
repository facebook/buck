""" Module docstring """

def _impl(ctx):
    """ Function docstring """
    out = ctx.declare_file("out.txt")
    out.write(out, " ".join(ctx.attr.contacts))

some_rule_test = rule(
    attrs = {},
    implementation = _impl,
    test = True,
)
