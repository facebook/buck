""" Module docstring """

def _impl(ctx):
    if ctx.label.name == "no_defaults":
        if ctx.attr.a != 1:
            fail("expected attr.a to equal '1'")
        if ctx.attr.b != "foo_value":
            fail("expected attr.b to equal 'foo_value'")
    elif ctx.label.name == "defaults":
        if ctx.attr.a != 0:
            fail("expected attr.a to equal '0'")
        if ctx.attr.b != "":
            fail("expected attr.b to equal ''")
    else:
        fail("invalid target name")

my_rule = rule(
    attrs = {
        "a": attr.int(),
        "b": attr.string(),
    },
    implementation = _impl,
)
