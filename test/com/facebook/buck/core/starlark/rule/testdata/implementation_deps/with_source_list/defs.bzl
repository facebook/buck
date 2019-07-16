""" Module docstring """

def _write_file_impl(ctx):
    """ Write a file """
    f = ctx.actions.declare_file(ctx.attr.filename)
    ctx.actions.write(output=f, content=ctx.attr.content, is_executable=ctx.attr.executable)

def _noop_impl(_ctx):
    """ Do nothing """
    pass

write_file = rule(
    attrs = {
        "filename": attr.string(),
        "executable": attr.bool(),
        "content": attr.string(),
    },
    implementation = _write_file_impl,
)

noop = rule(
    attrs = {"srcs": attr.source_list(default = [
        "//with_source_list:default",
        "default_src.txt",
    ])},
    implementation = _noop_impl,
)
