""" Module docstring """

def _write_file_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.write(f, "contents")

def _source_list_rule_impl(ctx):
    if len(ctx.attr.srcs) != 2:
        fail("Expected two sources")
    first = ctx.attr.srcs[0].short_path.replace("\\", "/")
    second = ctx.attr.srcs[1].short_path.replace("\\", "/")

    expected = ["src.txt", "file__/out.txt"]

    if first.endswith(expected[0]):
        expected.remove(expected[0])
    elif first.endswith(expected[1]):
        expected.remove(expected[1])

    if second.endswith(expected[0]):
        expected.remove(expected[0])
    elif second.endswith(expected[1]):
        expected.remove(expected[1])

    if len(expected) != 0:
        fail("Expected shortpaths to endswith {}, but there was none".format(expected))

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
