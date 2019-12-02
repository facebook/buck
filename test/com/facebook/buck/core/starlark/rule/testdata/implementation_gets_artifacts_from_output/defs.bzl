""" Module docstring """

def _output_rule_impl(ctx):
    output = ctx.attr.output.short_path.replace("\\", "/")
    expected_output = "{}__/some_out.txt".format(ctx.label.name)
    if not output.endswith(expected_output):
        fail("Expected short path endswith {}, got {}".format(expected_output, output))

    if ctx.attr.contents:
        ctx.actions.write(ctx.attr.output, ctx.attr.contents)

output_rule = rule(
    attrs = {
        "output": attr.output(),
        "contents": attr.string(),
    },
    implementation = _output_rule_impl,
)

output_rule_with_default = rule(
    attrs = {
        "output": attr.output(
            default = "out.txt",
            mandatory = False,
        ),
        "contents": attr.string(),
    },
    implementation = _output_rule_impl,
)
