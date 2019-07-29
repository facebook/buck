""" Module docstring """

def _write_file_impl(ctx):
    f = ctx.actions.declare_file("out.txt")
    ctx.actions.write(f, "contents")

def _dep_rule_impl(ctx):
    dep = list(ctx.attr.dep[DefaultInfo].default_outputs)[0].short_path.replace("\\", "/")
    expected_dep = "file__/out.txt"
    if dep != expected_dep:
        fail("Expected short path {}, got {}".format(expected_dep, dep))

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
