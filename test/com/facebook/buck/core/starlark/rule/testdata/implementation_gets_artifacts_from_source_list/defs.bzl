""" Module docstring """

def _write_file_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.write(f, "contents")

def _source_list_rule_impl(ctx):
    if len(ctx.attr.srcs) != 2:
        fail("Expected two sources")
    first = ctx.attr.srcs[0].short_path.replace("\\", "/")
    second = ctx.attr.srcs[1].short_path.replace("\\", "/")
    expected_first = "src.txt"
    expected_second = "file__/out.txt"
    if not first.endswith(expected_first):
        fail("Expected short path endswith {}, got {}".format(expected_first, first))
    if not second.endswith(expected_second):
        fail("Expected short path endswith {}, got {}".format(expected_second, second))

    f = ctx.actions.declare_file("out2.txt")
    ctx.actions.write(f, "contents2")

write_file = rule(
    attrs = {},
    implementation = _write_file_impl,
)

source_list_rule = rule(
    attrs = {"srcs": attr.source_list()},
    implementation = _source_list_rule_impl,
)
