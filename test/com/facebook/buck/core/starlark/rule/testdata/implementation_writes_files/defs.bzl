""" Module docstring """

def _impl(ctx):
    if ctx.attr.as_string:
        out = ctx.actions.write(
            output = ctx.attr.filename,
            content = ctx.attr.content,
            is_executable = ctx.attr.executable,
        )

    else:
        f = ctx.actions.declare_file(ctx.attr.filename)
        out = ctx.actions.write(
            output = f,
            content = ctx.attr.content,
            is_executable = ctx.attr.executable,
        )
    if not out.short_path.replace("\\", "/").endswith(ctx.attr.filename):
        fail(
            "Expected artifact with name {}, but got {}".format(ctx.attr.filename, out),
        )

my_rule = rule(
    attrs = {
        "as_string": attr.bool(),
        "content": attr.string(),
        "executable": attr.bool(),
        "filename": attr.string(),
    },
    implementation = _impl,
)
