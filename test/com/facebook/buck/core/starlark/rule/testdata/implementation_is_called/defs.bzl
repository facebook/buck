""" Module docstring """

def _impl(ctx):
    if repr(ctx) != "<ctx>":
        fail("Did not get a correct ctx object")
    if ctx.label.package != "foo":
        fail("Expected to be called from //foo")
    if ctx.label.name != "bar":
        fail("Expected to be called with name 'bar'")

my_rule = rule(
    attrs = {"a": attr.int()},
    implementation = _impl,
)
