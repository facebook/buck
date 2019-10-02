""" Module docstring """

def _impl(ctx):
    # Make sure that hidden attrs have default values enabled, and that
    # they are coerced properly
    if ctx.attr._hidden[0].short_path.replace("\\", "/") != "foo/main.cpp":
        fail("expected attr._hidden to equal 'foo/main.cpp'")
    if ctx.label.name == "no_defaults":
        if ctx.attr.int != 1:
            fail("expected attr.int to equal '1'")
        if ctx.attr.string != "foo_value":
            fail("expected attr.string to equal 'foo_value'")
        if ctx.attr.string_list != ["foo", "baz"]:
            fail("expected attr.string_list to equal ['foo', 'baz']")
        if ctx.attr.int_list != [3, 4]:
            fail("expected attr.int_list to equal [3, 4]")
    elif ctx.label.name == "defaults":
        if ctx.attr.int != 0:
            fail("expected attr.int to equal '0'")
        if ctx.attr.string != "":
            fail("expected attr.string to equal ''")
        if ctx.attr.string_list != ["foo", "bar"]:
            fail("expected attr.string_list to equal ['foo', 'bar']")
        if ctx.attr.int_list != [1, 2]:
            fail("expected attr.int_list to equal [1, 2]")
    else:
        fail("invalid target name")

my_rule = rule(
    attrs = {
        "int": attr.int(),
        "string": attr.string(),
        "string_list": attr.string_list(default = [
            "foo",
            "bar",
        ]),
        "int_list": attr.int_list(default = [
            1,
            2,
        ]),
        "_hidden": attr.source_list(default = ["main.cpp"]),
    },
    implementation = _impl,
)
