""" Module docstring """

def _write_file_impl(ctx):
    """ Write a file """
    f = ctx.actions.declare_file(ctx.attr.filename)
    ctx.actions.write(output = f, content = ctx.attr.content, is_executable = ctx.attr.executable)

def _noop_impl(_ctx):
    """ Do nothing """
    pass

write_file = rule(
    attrs = {
        "content": attr.string(),
        "executable": attr.bool(),
        "filename": attr.string(),
    },
    implementation = _write_file_impl,
)

noop = rule(
    attrs = {
        "src_file": attr.source(
            default = "default_src.txt",
        ),
        "src_target": attr.source(
            default = "//with_source:default",
        ),
        # Ensure that these values are actually used for deps, not just
        # made available to users' implementation functions
        "_hidden_src_file": attr.source(
            default = "hidden_src.txt",
        ),
        "_hidden_src_target": attr.source(
            default = "//with_source:hidden",
        ),
    },
    implementation = _noop_impl,
)
