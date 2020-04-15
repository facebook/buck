""" Module docstring """

def _impl(ctx):
    ctx.actions.write("out.txt", "some contents")

my_rule = rule(
    attrs = {},
    implementation = _impl,
)
