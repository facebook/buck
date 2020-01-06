""" Module docstring """

def _impl(ctx):
    f = ctx.actions.declare_file(ctx.attr.filename)
    ctx.actions.write(output = f, content = ctx.attr.content, is_executable = ctx.attr.executable)

my_rule = rule(
    attrs = {
        "content": attr.string(),
        "executable": attr.bool(),
        "filename": attr.string(),
    },
    implementation = _impl,
)
