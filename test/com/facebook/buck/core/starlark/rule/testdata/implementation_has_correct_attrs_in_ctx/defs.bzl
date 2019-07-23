""" Module docstring """

def _impl(ctx):
    # Make sure that hidden attrs have default values enabled, and that
    # they are coerced properly
    if ctx.attr._hidden[0].short_path.replace("\\", "/") != "foo/main.cpp":
        fail("expected attr._hidden to equal 'foo/main.cpp'")
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
        "_hidden": attr.source_list(default = ["main.cpp"]),
    },
    implementation = _impl,
)
