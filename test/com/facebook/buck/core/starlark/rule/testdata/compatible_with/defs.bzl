""" Module docstring """

def _write_file_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.write(f, "contents")

write_file = rule(
    attrs = {},
    implementation = _write_file_impl,
)
