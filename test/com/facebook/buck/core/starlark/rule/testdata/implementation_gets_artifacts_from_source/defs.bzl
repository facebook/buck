""" Module docstring """

def _write_file_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.write(f, "contents")

def _source_rule_impl(ctx):
    src_file = ctx.attr.src_file.short_path.replace("\\", "/")
    src_target = ctx.attr.src_target.short_path.replace("\\", "/")
    expected_src_file = "src.txt"
    expected_src_target = "file__/out.txt"
    if not src_file.endswith(expected_src_file):
        fail("Expected short path endswith {}, got {}".format(expected_src_file, src_file))
    if not src_target.endswith(expected_src_target):
        fail("Expected short path endwith {}, got {}".format(expected_src_target, src_target))

    f = ctx.actions.declare_file("out2.txt")
    ctx.actions.write(f, "contents2")

write_file = rule(
    attrs = {},
    implementation = _write_file_impl,
)

source_rule = rule(
    attrs = {
        "src_file": attr.source(),
        "src_target": attr.source(),
    },
    implementation = _source_rule_impl,
)
