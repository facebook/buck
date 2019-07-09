""" Module docstring """

def _impl(ctx):
    f = ctx.actions.declare_file(ctx.attr.filename)
    expected = "foo/valid_filename__/bar/baz.sh"
    short_path = f.short_path.replace("\\", "/")
    if short_path != expected:
        fail("Expected {}, got {} for short_path of declared file".format(expected, short_path))

my_rule = rule(
    attrs = {"filename": attr.string()},
    implementation = _impl,
)
