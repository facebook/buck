""" Module docstring """

def _write_file_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.write(f, "contents")

def _dep_rule_impl(ctx):
    if str(ctx.attr.dep.label) != "//:file":
        fail("expected label //:file, got {}".format(ctx.attr.dep.label))

    dep_file = list(ctx.attr.dep[DefaultInfo].default_outputs)[0].short_path.replace("\\", "/")
    expected_dep_file = "file__/out.txt"
    if not dep_file.endswith(expected_dep_file):
        fail("Expected short path endswith {}, got {}".format(expected_dep_file, dep_file))

    f = ctx.actions.declare_file("out2.txt")
    ctx.actions.write(f, "contents2")

write_file = rule(
    attrs = {},
    implementation = _write_file_impl,
)

dep_rule = rule(
    attrs = {
        "dep": attr.dep(),
    },
    implementation = _dep_rule_impl,
)
