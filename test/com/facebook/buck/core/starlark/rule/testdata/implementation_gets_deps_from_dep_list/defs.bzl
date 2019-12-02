""" Module docstring """

def _write_file_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.write(f, "contents")

def _dep_list_rule_impl(ctx):
    if len(ctx.attr.deps) != 2:
        fail("Expected two deps")

    if str(ctx.attr.deps[0].label) != "//:file1":
        fail("expected label //:file1, got {}".format(ctx.attr.deps[0].label))
    if str(ctx.attr.deps[1].label) != "//:file2":
        fail("expected label //:file2, got {}".format(ctx.attr.deps[1].label))

    first_dep = ctx.attr.deps[0][DefaultInfo]
    second_dep = ctx.attr.deps[1][DefaultInfo]

    first = list(first_dep.default_outputs)[0].short_path.replace("\\", "/")
    second = list(second_dep.default_outputs)[0].short_path.replace("\\", "/")
    expected_first = "file1__/out.txt"
    expected_second = "file2__/out.txt"
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

dep_list_rule = rule(
    attrs = {"deps": attr.dep_list()},
    implementation = _dep_list_rule_impl,
)
