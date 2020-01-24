""" Module docstring """

def _impl(ctx):
    f = ctx.actions.declare_file(ctx.attr.filename)
    expected = "foo/valid_filename__/bar/baz.sh"
    short_path = f.short_path.replace("\\", "/")
    if not short_path.endswith(expected):
        fail("Expected short_path {} endswith {} for declared file".format(short_path, expected))

    ctx.actions.write(output = f, content = "", is_executable = False)

my_rule = rule(
    attrs = {"filename": attr.string()},
    implementation = _impl,
)
